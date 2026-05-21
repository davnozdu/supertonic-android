package com.brahmadeo.supertonic.tts.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf

/**
 * Lightweight in-memory ring buffer surfaced to the UI via [DebugLogPanel].
 *
 * The user's OEM (OnePlus / OxygenOS / Android 16) drops third-party app
 * logs out of the adb logcat read window, so we keep a short live feed in
 * the app itself for development verification of LiteRT and other native
 * subsystems. Nothing is persisted across process restarts.
 */
object DebugLog {
    enum class Level { INFO, WARN, ERROR }

    data class Entry(val level: Level, val text: String)

    private const val MAX_LINES = 10
    val entries = mutableStateListOf<Entry>()
    private val ui = Handler(Looper.getMainLooper())

    fun i(msg: String) = add(Level.INFO, msg)
    fun w(msg: String) = add(Level.WARN, msg)
    fun e(msg: String) = add(Level.ERROR, msg)

    private fun add(level: Level, msg: String) {
        when (level) {
            Level.INFO -> Log.i("DebugLog", msg)
            Level.WARN -> Log.w("DebugLog", msg)
            Level.ERROR -> Log.e("DebugLog", msg)
        }
        ui.post {
            entries.add(Entry(level, msg))
            while (entries.size > MAX_LINES) entries.removeAt(0)
        }
    }
}
