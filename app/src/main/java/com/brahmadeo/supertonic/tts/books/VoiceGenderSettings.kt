package com.brahmadeo.supertonic.tts.books

import android.content.Context
import com.brahmadeo.supertonic.tts.utils.AssetManager

/** Voice gender belongs to model+voice, shared by books. User choices override the bundled defaults. */
object VoiceGenderSettings {
    fun key(ctx: Context, voice: String): String = BookVoiceRef.parse(voice)?.key ?: BookVoiceRef(AssetManager.getModelType(ctx), voice).key
    fun gender(ctx: Context, voice: String): String? {
        val configured = ctx.getSharedPreferences("voice_genders", 0).getString(key(ctx, voice), null)
        // A library Tera voice takes its gender from the manifest, which a fresh process (the TTS service) may not have
        // read yet: load it here, where a context is at hand.
        if (configured == null && com.brahmadeo.supertonic.tts.tera.TeraVoiceLibrary.isLibrary(BookVoiceRef.parse(voice)?.voice ?: voice))
            runCatching { com.brahmadeo.supertonic.tts.tera.TeraVoiceLibrary.voices(ctx) }
        return if (configured != null) configured.takeIf { it in listOf("m", "f") } else BookVoiceAssign.gender(voice)
    }
    fun save(ctx: Context, voice: String, gender: String?) {
        require(gender == null || gender in listOf("m", "f", "?"))
        val ref = key(ctx, voice); require(BookVoiceRef.parse(ref) != null)
        ctx.getSharedPreferences("voice_genders", 0).edit().apply { if (gender == null) remove(ref) else putString(ref, gender) }.apply()
        BookVoices.clear(); com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }
}
