package com.brahmadeo.supertonic.tts.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.brahmadeo.supertonic.tts.utils.Npu
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Main-process handle of [GemmaNpuService]. Same calls as the in-process model; a crash of the Gemma
 * process surfaces as an ordinary provider error, releases the HTP turn it held and makes the next
 * load wait [Npu.retryDelayMs] (30 s, 1, 2, 4, 5 min). The NPU engine is never switched off. */
internal object GemmaNpuClient {
    private const val TAG = "GemmaNpu"
    @Volatile private var failures = 0
    @Volatile private var retryAt = 0L

    private val gate = object : IHtpGate.Stub() {
        override fun lock() { Npu.lockForRemote() }
        override fun unlock() { Npu.unlockForRemote() }
    }

    class Remote internal constructor(private val context: Context, private val connection: ServiceConnection,
                                      private val service: IGemmaNpu) : AutoCloseable {
        @Volatile var alive = true; private set
        private val death = IBinder.DeathRecipient { crashed() }
        init { service.asBinder().linkToDeath(death, 0) }

        @Synchronized internal fun crashed() {
            if (!alive) return
            alive = false
            failures++
            retryAt = SystemClock.elapsedRealtime() + Npu.retryDelayMs(failures)
            Npu.releaseRemote()
            Log.w(TAG, "Gemma NPU process died (failure $failures); retry in ${Npu.retryDelayMs(failures) / 1000} s, reading continues")
            runCatching { context.unbindService(connection) }
        }

        private inline fun <T> call(block: () -> T): T = try { block() } catch (e: RemoteException) {
            crashed(); throw IllegalStateException("Gemma NPU: процесс модели перезапускается", e)
        }

        fun generate(prompt: String, maxTokens: Int, slot: Int = 0): String =
            call { service.generate(prompt, maxTokens, slot) }.also { failures = 0 }
        fun cancel() { if (alive) runCatching { service.cancel() } }
        fun stats(): String = if (alive) runCatching { service.stats() }.getOrDefault("") else ""
        @Synchronized override fun close() {
            if (!alive) return
            alive = false
            runCatching { service.asBinder().unlinkToDeath(death, 0) }
            runCatching { context.unbindService(connection) }
            Npu.releaseRemote()
        }
    }

    /** Binds (starting the process) and loads the model there. Blocks; never call on the main thread. */
    fun load(context: Context, model: File, contextTokens: Int = 4096, threads: Int = 2): Remote {
        val wait = retryAt - SystemClock.elapsedRealtime()
        check(wait <= 0) { "Gemma NPU: перезапуск после сбоя через ${(wait + 999) / 1000} с" }
        val app = context.applicationContext
        val ready = CountDownLatch(1)
        var bound: IGemmaNpu? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { bound = IGemmaNpu.Stub.asInterface(binder); ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        check(app.bindService(Intent(app, GemmaNpuService::class.java), connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) {
            "Gemma NPU: сервис недоступен"
        }
        val service = if (ready.await(15, TimeUnit.SECONDS)) bound else null
        if (service == null) { runCatching { app.unbindService(connection) }; throw IllegalStateException("Gemma NPU: процесс не запустился") }
        // linkToDeath() throws when the process already died: the binding made above must not leak.
        val remote = try { Remote(app, connection, service) } catch (e: Exception) {
            runCatching { app.unbindService(connection) }
            failures++
            retryAt = SystemClock.elapsedRealtime() + Npu.retryDelayMs(failures)
            throw IllegalStateException("Gemma NPU: процесс модели упал при подключении", e)
        }
        // A load that never returns (driver/DSP hang) would block the single LLM worker for good: it is bounded,
        // and the stuck process is killed, which counts as a crash (backoff, then a fresh process).
        val loading = java.util.concurrent.CompletableFuture<Unit>()
        Thread({ try { service.load(model.absolutePath, contextTokens, threads, gate); loading.complete(Unit) }
            catch (t: Throwable) { loading.completeExceptionally(t) } }, "Gemma-NPU-load").apply { isDaemon = true }.start()
        try { loading.get(loadTimeoutMs, TimeUnit.MILLISECONDS) } catch (e: java.util.concurrent.TimeoutException) {
            killProcess(app); remote.crashed()
            throw IllegalStateException("Gemma NPU: загрузка не завершилась за ${loadTimeoutMs / 1000} с, процесс перезапускается")
        } catch (e: java.util.concurrent.ExecutionException) {
            when (val cause = e.cause) {
                is RemoteException -> { remote.crashed(); throw IllegalStateException("Gemma NPU: процесс модели упал при загрузке", cause) }
                is Exception -> { remote.close(); throw cause }
                else -> { remote.close(); throw e }
            }
        }
        return remote
    }

    @Volatile internal var loadTimeoutMs = 90_000L // loads take 1-5 s from the cache; tests shorten it
    private fun killProcess(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.runningAppProcesses?.firstOrNull { it.processName == context.packageName + ":gemma_npu" }?.let {
            Log.w(TAG, "Gemma NPU load hung; killing pid ${it.pid}")
            android.os.Process.killProcess(it.pid)
        }
    }
}
