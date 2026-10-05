package com.brahmadeo.supertonic.tts.utils

import android.content.Context

object EngineThreads {
    val maximum: Int get() = EngineThreadPolicy.maximum(Runtime.getRuntime().availableProcessors())
    fun recommended(model: String): Int = EngineThreadPolicy.recommended(model, maximum)
    fun selected(context: Context, model: String = AssetManager.getModelType(context)): Int {
        val prefs = context.getSharedPreferences("SupertonicPrefs", 0)
        val key = EngineThreadPolicy.key(model)
        return EngineThreadPolicy.selected(model, maximum, if (prefs.contains(key)) prefs.getInt(key, 4) else null)
    }
    fun save(context: Context, model: String, threads: Int) {
        val value = EngineThreadPolicy.selected(model, maximum, threads)
        context.getSharedPreferences("SupertonicPrefs", 0).edit().putInt(EngineThreadPolicy.key(model), value).apply()
    }
}
