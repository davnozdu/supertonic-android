package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The cleaner: proves that nothing of the NPU stays in memory when it is not used, and forces it out if it does.
 *
 * Measured on the phone (CPH2745, 3 ноября 2026): closing an NPU session really returns everything — Kokoro NPU went
 * from 1.32 GB of dmabuf and 389 MB PSS to zero in the same process. What can stay behind is a *held* session: an engine
 * that its idle timer missed, a worker process that outlived its job, or a model whose owner kept it for speed. This
 * sweep runs every minute, and when nothing has used the NPU for [IDLE_MS] it closes everything it can and writes the
 * numbers (PSS + dmabuf) before and after to the log, so "память не освободилась" is a fact with a number, not a guess.
 * If the buffers survive the sweep for [HARD_IDLE_MS] of full idleness, the process is restarted — the only thing that
 * always returns them.
 */
object NpuJanitor {
    private const val TAG = "Npu"
    private const val IDLE_MS = 180_000L          // 3 min without NPU/audio → everything is unloaded
    private const val RESIDUAL_MB = 120L          // more dmabuf than this means something is still held
    // 60 мин простоя и буферы всё ещё держатся → перезапуск процесса. Было 15: читалка на долгой паузе теряла движок
    // (переподключалась); перезапуск — редкая страховка, цена — лишь более долгий первый запуск после простоя.
    private const val HARD_IDLE_MS = 3_600_000L
    private const val SWEEP_MS = 60_000L

    @Volatile private var lastUse = SystemClock.elapsedRealtime()
    @Volatile private var releaseAll: (() -> Unit)? = null
    @Volatile private var idleExtra: (() -> Long)? = null
    @Volatile private var lastRestart = 0L
    private var started = false

    /** Any NPU work in this process: the sweep must not close a model that is being used. */
    fun used() { lastUse = SystemClock.elapsedRealtime() }

    /** Called once by the application: [release] closes every engine, [idle] says how long ago audio was made. */
    fun start(context: Context, release: () -> Unit, idle: () -> Long) {
        synchronized(this) { if (started) return; started = true }
        releaseAll = release
        idleExtra = idle
        val app = context.applicationContext
        // Без записи «started» на каждый запуск процесса: в файл идёт только то, что уборщик сделал (бережём флеш).
        ScheduledThreadPoolExecutor(1) { r -> Thread(r, "NpuJanitor").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
            .scheduleWithFixedDelay({ runCatching { sweep(app) }.onFailure { Log.w(TAG, "janitor: ${it.javaClass.simpleName}: ${it.message}") } },
                SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS)
    }

    private fun idleFor(): Long {
        val audio = idleExtra?.invoke() ?: Long.MAX_VALUE
        return minOf(SystemClock.elapsedRealtime() - lastUse, audio)
    }

    private fun sweep(context: Context) {
        // A running preparation or sound generation owns the NPU: not a moment to close anything.
        if (com.brahmadeo.supertonic.tts.books.prepare.BookPreparation.state.value.running) return
        if (com.brahmadeo.supertonic.tts.ambience.AmbienceGeneration.state.value.running) return
        // Audio playing from the queue: a model closed now would be reloaded under the speaker.
        if (com.brahmadeo.supertonic.tts.service.PlaybackService.playing) return
        killOrphanWorker(context)
        val idle = idleFor()
        if (idle < IDLE_MS) return
        val before = Npu.footprint(context)
        if (dmabufMb() < RESIDUAL_MB) return
        note(context, "idle ${idle / 1000} s, still held — $before; closing every model")
        Log.i(TAG, "janitor: NPU idle ${idle / 1000} s, still held — $before; closing every model and session")
        runCatching { releaseAll?.invoke() }
        runCatching { com.brahmadeo.supertonic.tts.llm.LlmProviders.unload() }
        // The buffers go away a moment after close(): measure once more, and say so in the log.
        Thread {
            Thread.sleep(4000)
            val after = Npu.footprint(context)
            note(context, "after release — $after")
            Log.i(TAG, "janitor: after release $after")
        }.apply { isDaemon = true }.start()
        if (idle > HARD_IDLE_MS && SystemClock.elapsedRealtime() - lastRestart > HARD_IDLE_MS) {
            Thread {
                Thread.sleep(10_000)
                if (dmabufMb() >= RESIDUAL_MB) {
                    lastRestart = SystemClock.elapsedRealtime()
                    Log.w(TAG, "janitor: ${Npu.footprint(context)} still held after release and ${idle / 60_000} min idle — restarting the process")
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }.apply { isDaemon = true }.start()
        }
    }

    /** The generator's process must live only while its service does: a leftover process holds the whole sound model. */
    private fun killOrphanWorker(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val running = runCatching { am.getRunningServices(Int.MAX_VALUE).any { it.service.packageName == context.packageName &&
            it.service.className == com.brahmadeo.supertonic.tts.ambience.SoundGenService::class.java.name } }.getOrDefault(true)
        if (running) return
        val proc = runCatching { am.runningAppProcesses?.firstOrNull { it.processName == context.packageName + ":soundgen" } }.getOrNull() ?: return
        Log.w(TAG, "janitor: sound generator process ${proc.pid} outlived its service — killing it")
        note(context, "killed orphan generator process ${proc.pid}")
        runCatching { android.os.Process.killProcess(proc.pid) }
    }

    /** The cleaner's own record: the app log is cut by the firmware after a few lines, this file stays. */
    private fun note(context: Context, text: String) = runCatching {
        val f = File(context.cacheDir, "npu-janitor.txt")
        val lines = if (f.isFile) f.readLines().takeLast(19) else emptyList()
        f.writeText((lines + "${java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $text").joinToString("\n") + "\n")
    }

    /** dmabuf mapped into this process, MB. */
    fun dmabufMb(): Long {
        var bytes = 0L
        runCatching {
            File("/proc/self/maps").forEachLine { line ->
                if (line.contains("dmabuf") || line.contains("fastrpc")) {
                    val range = line.substringBefore(' ')
                    val dash = range.indexOf('-')
                    if (dash > 0) runCatching { bytes += range.substring(dash + 1).toLong(16) - range.substring(0, dash).toLong(16) }
                }
            }
        }
        return bytes / 1048576
    }
}
