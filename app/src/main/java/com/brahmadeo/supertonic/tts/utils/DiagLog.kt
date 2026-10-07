package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.util.Log

/** Per-sentence traces (routing, text/synth traces, cache hits, ahead PCM, per-call synthesis timings): several
 * lines per sentence for hours of reading cost logd CPU and are dropped by the OnePlus quota anyway. Printed only
 * with the "verbose_logs" pref or while SpeechDiagnostics runs; errors and lifecycle events always log. */
object DiagLog {
    @Volatile var verbose = false
    fun init(ctx: Context) { if (ctx.getSharedPreferences("SupertonicPrefs", 0).getBoolean("verbose_logs", false)) verbose = true }
    fun i(tag: String, message: String) { if (verbose) Log.i(tag, message) }
}
