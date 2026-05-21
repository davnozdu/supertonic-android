package com.brahmadeo.supertonic.tts.tflite

import com.brahmadeo.supertonic.tts.ui.DebugLog

/**
 * Compile-time and runtime probe for LiteRT (TensorFlow Lite).
 * Iteration 1 of the INT4 rollout: we only confirm the AAR's classes and
 * native libs are reachable. No model is loaded here, the pipeline still runs
 * exclusively on ORT.
 */
object TFLiteAvailability {
    fun probe() {
        try {
            val opts = org.tensorflow.lite.Interpreter.Options()
            DebugLog.i("LiteRT classes OK: ${opts.javaClass.simpleName}")
        } catch (t: Throwable) {
            DebugLog.e("LiteRT not available: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
