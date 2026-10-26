package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import com.brahmadeo.supertonic.tts.books.BookVoiceRef
import com.brahmadeo.supertonic.tts.books.VoiceGenderSettings
import com.brahmadeo.supertonic.tts.tera.TeraCustomVoices
import com.brahmadeo.supertonic.tts.tera.TeraVoiceLibrary
import java.io.File

/**
 * «Удалить голос» (long press in the voice list), for every model. Imported and downloaded Tera voices are deleted
 * from the phone (a downloaded one comes back with «Скачать голоса Tera»); built-in voices live inside their model,
 * so they are hidden from every list — main screen, roles, book characters — and can be brought back. A book or
 * role that used a hidden voice falls back as for any missing voice.
 */
object HiddenVoices {
    private const val KEY = "hidden_voices"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)

    fun all(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY, emptySet()).orEmpty()
    fun hidden(ctx: Context, model: String): Set<String> =
        all(ctx).mapNotNull { BookVoiceRef.parse(it) }.filter { it.model == model }.map { it.voice }.toSet()
    fun filter(ctx: Context, model: String, voices: List<String>): List<String> {
        val hidden = hidden(ctx, model)
        return if (hidden.isEmpty()) voices else voices.filter { it !in hidden }
    }

    enum class Result { DELETED, HIDDEN, LAST_VOICE }

    /** Deletes or hides [voice] of [model]; [remaining] is the model's list before the change. */
    fun remove(ctx: Context, model: String, voice: String, remaining: List<String>): Result {
        if (remaining.count { it != voice } == 0) return Result.LAST_VOICE
        val key = BookVoiceRef(model, voice).key
        val result = when {
            model == AssetManager.TERA_MODEL && TeraCustomVoices.isCustom(voice) -> { TeraCustomVoices.delete(ctx, voice); Result.DELETED }
            model == AssetManager.TERA_MODEL && TeraVoiceLibrary.isLibrary(voice) -> {
                // Deleted, and remembered as removed so the download button does not bring it back on its own.
                File(TeraVoiceLibrary.root(ctx), voice).deleteRecursively(); add(ctx, key); Result.DELETED
            }
            else -> { add(ctx, key); Result.HIDDEN }
        }
        if (model == AssetManager.TERA_MODEL && (TeraCustomVoices.isCustom(voice))) runCatching { VoiceGenderSettings.save(ctx, key, null) }
        SpeechPreparationCache.clear()
        return result
    }

    /** Brings back every hidden voice of [model] (downloaded Tera voices: the download button offers them again). */
    fun restore(ctx: Context, model: String) {
        val keep = all(ctx).filter { BookVoiceRef.parse(it)?.model != model }.toSet()
        prefs(ctx).edit().putStringSet(KEY, keep).apply()
        SpeechPreparationCache.clear()
    }

    private fun add(ctx: Context, key: String) {
        prefs(ctx).edit().putStringSet(KEY, all(ctx) + key).apply()
    }
}
