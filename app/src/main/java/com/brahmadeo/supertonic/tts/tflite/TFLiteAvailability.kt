package com.brahmadeo.supertonic.tts.tflite

import android.util.Log

/**
 * Compile-time and runtime probe for LiteRT (TensorFlow Lite).
 * Iteration 1 of the INT4 rollout: we only confirm the AAR's classes and
 * native libs are reachable. No model is loaded here, the pipeline still runs
 * exclusively on ORT.
 */
object TFLiteAvailability {
    private const val TAG = "TFLiteAvailability"

    fun probe() {
        try {
            // Touch the real class so the linker resolves libLiteRt.so on first use.
            val opts = org.tensorflow.lite.Interpreter.Options()
            Log.i(TAG, "LiteRT available, Options class loaded: $opts")
        } catch (t: Throwable) {
            Log.w(TAG, "LiteRT not available: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
