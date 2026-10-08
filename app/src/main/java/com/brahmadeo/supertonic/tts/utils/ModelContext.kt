package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences

/** Read-only model selection for one engine. Never writes the user's global selected_model. */
class ModelContext(context: Context, val model: String) : ContextWrapper(context.applicationContext) {
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val original = super.getSharedPreferences(name, mode)
        if (name != "SupertonicPrefs") return original
        return object : SharedPreferences by original {
            override fun getString(key: String?, defValue: String?): String? = if (key == "selected_model") model else original.getString(key, defValue)
            override fun getAll(): MutableMap<String, *> = original.all.toMutableMap().apply { put("selected_model", model) }
        }
    }
}
