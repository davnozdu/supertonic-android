package com.brahmadeo.supertonic.tts.utils

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.brahmadeo.supertonic.tts.llm.IHtpGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app-wide HTP turn for NPU work that runs outside the main process.
 *
 * The sound generator lives in its own process (":soundgen"), so its QNN graphs would otherwise run at the same time
 * as the reader's Kokoro/Tera graphs and Gemma: the DSP does not survive that (QNN 1002, both sides 4× slower). The turn
 * itself always lives in the main process ([Npu.lockForRemote]); this service is how another process asks for it.
 *
 * A worker that dies while holding the turn (o-kill under memory pressure, a QNN abort) is released by the death
 * watch: otherwise the reader would wait for the NPU forever.
 */
class HtpGateService : Service() {
    override fun onBind(intent: Intent?): IBinder = binder

    private val held = HashMap<Int, Int>()          // caller pid → turns it holds
    private val tokens = HashMap<Int, IBinder>()    // caller pid → its death token

    private val binder = object : IHtpGate.Stub() {
        override fun lock() {
            val pid = Binder.getCallingPid()
            synchronized(held) { held[pid] = (held[pid] ?: 0) + 1 }
            Npu.lockForRemote()
        }

        override fun unlock() {
            val pid = Binder.getCallingPid()
            Npu.unlockForRemote()
            synchronized(held) {
                val n = held[pid] ?: return
                if (n <= 1) held.remove(pid) else held[pid] = n - 1
            }
        }

        override fun watch(client: IBinder?) {
            if (client == null) return
            val pid = Binder.getCallingPid()
            val death = IBinder.DeathRecipient {
                val turns = synchronized(held) { val n = held.remove(pid) ?: 0; tokens.remove(pid); n }
                repeat(turns) { Npu.unlockForRemote() }
                Log.w("Npu", "worker process $pid died holding $turns HTP turn(s); released")
            }
            runCatching { client.linkToDeath(death, 0) }.onFailure { return }
            synchronized(held) { tokens[pid] = client }
        }
    }

    override fun onDestroy() {
        // Turns held by whoever is still bound are dropped with this service; the workers fall back to their own lock.
        val turns = synchronized(held) { val n = held.values.sum(); held.clear(); tokens.clear(); n }
        if (turns > 0) { repeat(turns) { Npu.unlockForRemote() }; Log.w("Npu", "gate stopped with $turns turn(s) held; released") }
        super.onDestroy()
    }
}

/** Client side of [HtpGateService], used inside a worker process. */
object HtpGate {
    private const val TAG = "Npu"
    @Volatile private var connection: ServiceConnection? = null
    @Volatile var attached = false
        private set

    /** Binds to the main process and routes this process's HTP lock through it. Safe to call repeatedly. */
    @Synchronized fun attach(context: Context, timeoutMs: Long = 8000): Boolean {
        if (attached) return true
        val app = context.applicationContext
        val ready = CountDownLatch(1)
        var gate: IHtpGate? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                gate = IHtpGate.Stub.asInterface(binder); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { detach("gate disconnected") }
            override fun onBindingDied(name: ComponentName?) { detach("gate binding died") }
            override fun onNullBinding(name: ComponentName?) { detach("gate null binding") }
        }
        val bound = runCatching { app.bindService(Intent(app, HtpGateService::class.java), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        val readyNow = bound && ready.await(timeoutMs, TimeUnit.MILLISECONDS) && gate != null
        if (!readyNow) {
            runCatching { app.unbindService(conn) }
            Log.w(TAG, "HTP gate unavailable; NPU work here is serialized only inside this process")
            return false
        }
        val g = gate!!
        // Our own token: if this process dies mid-run, the gate drops the turn instead of blocking the reader.
        runCatching { g.watch(Binder()) }
        this.app = app
        connection = conn
        Npu.remoteHtp = g
        attached = true
        Log.i(TAG, "HTP gate attached; NPU turns are serialized with the reader")
        return true
    }

    @Volatile private var app: Context? = null

    /** Drops the binding (a bound service keeps the main process alive for nothing). NPU work then locks locally. */
    @Synchronized fun detach(reason: String) {
        val conn = connection ?: return
        connection = null
        Npu.remoteHtp = null
        attached = false
        Log.w(TAG, "HTP gate detached ($reason); this process locks the NPU on its own")
        runCatching { app?.unbindService(conn) }
    }
}
