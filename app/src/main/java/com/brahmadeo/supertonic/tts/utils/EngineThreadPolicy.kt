package com.brahmadeo.supertonic.tts.utils

/** Conservative defaults; the slider controls inference workers, not CPU affinity. */
object EngineThreadPolicy {
    const val PREFIX = "cpu_threads_"
    fun maximum(processors: Int): Int = processors.coerceIn(1, 16)
    fun recommended(model: String, processors: Int): Int =
        (if (model == "teratts_v2" || model == "standard" || model.startsWith("android_optimized")) 6 else 4)
            .coerceIn(1, maximum(processors))
    fun selected(model: String, processors: Int, saved: Int?): Int =
        (saved ?: recommended(model, processors)).coerceIn(1, maximum(processors))
    fun key(model: String): String = PREFIX + model
}
