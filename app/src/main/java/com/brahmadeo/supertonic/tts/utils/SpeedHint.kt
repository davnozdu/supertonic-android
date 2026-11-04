package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import java.io.File

/** Подсказка системе, как быстро нужен синтез (ADPF, Android 12+), и аудиоприоритет потока синтеза — идея форка
 * TeraTTS (1.13): с погашенным экраном прошивки опускают частоты и вытесняют поток на медленные ядра, отсюда паузы
 * между фразами. Фразу надо успеть за половину её длительности; если не успеваем, система поднимает частоты
 * ядер, на которых работают потоки синтеза. На слабых телефонах помогает больше всего. На звучание не влияет. */
object SpeedHint {
    private const val TAG = "SpeedHint"
    private var session: Any? = null           // PerformanceHintManager.Session
    private var tids: IntArray = IntArray(0)
    private var failed = false

    /** Выполнить синтез [block] (вернёт PCM 16 бит моно [sampleRate]). [live] — фраза для текущего чтения (а не
     * подготовка наперёд): только её поток получает аудиоприоритет, чтобы упреждение не мешало интерфейсу. */
    fun synth(context: Context, sampleRate: Int, live: Boolean, block: () -> ByteArray): ByteArray {
        val me = Process.myTid()
        val old = if (live) runCatching { Process.getThreadPriority(me) }.getOrNull() else null
        if (old != null) runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val started = System.nanoTime()
        try {
            val pcm = block()
            report(context, pcm.size / 2f / sampleRate.coerceAtLeast(1), System.nanoTime() - started)
            return pcm
        } finally {
            if (old != null) runCatching { Process.setThreadPriority(old) }
        }
    }

    @Synchronized private fun report(context: Context, audioSeconds: Float, elapsedNs: Long) {
        if (Build.VERSION.SDK_INT < 31 || failed || audioSeconds <= 0.05f) return
        try {
            // Все потоки процесса: среди них потоки ONNX Runtime, PyTorch и Shtorm. Простаивающие подсказка не
            // нагружает — она лишь говорит, сколько времени есть у работы, которую потоки делают.
            val live = File("/proc/self/task").list()?.mapNotNull { it.toIntOrNull() }?.sorted()?.toIntArray() ?: return
            val s = session as? PerformanceHintManager.Session
            if (s == null) {
                val pm = context.getSystemService(PerformanceHintManager::class.java) ?: run { failed = true; return }
                session = pm.createHintSession(live, (audioSeconds * 0.5f * 1e9f).toLong().coerceAtLeast(10_000_000L))
                tids = live
                if (session == null) { failed = true; Log.i(TAG, "performance hint: not supported here"); return }
                Log.i(TAG, "performance hint: on, ${live.size} threads")
            } else if (!live.contentEquals(tids) && Build.VERSION.SDK_INT >= 34) {
                s.setThreads(live); tids = live
            }
            val hint = session as PerformanceHintManager.Session
            hint.updateTargetWorkDuration((audioSeconds * 0.5f * 1e9f).toLong().coerceAtLeast(10_000_000L))
            hint.reportActualWorkDuration(elapsedNs.coerceAtLeast(1L))
        } catch (t: Throwable) {
            // Поток мог завершиться между чтением списка и вызовом: в следующий раз сессия создаётся заново.
            runCatching { (session as? PerformanceHintManager.Session)?.close() }
            session = null; tids = IntArray(0)
        }
    }
}
