package com.brahmadeo.supertonic.tts.books

import android.content.Context
import com.brahmadeo.supertonic.tts.llm.MultiVoiceSettings
import com.brahmadeo.supertonic.tts.llm.VoiceRole
import com.brahmadeo.supertonic.tts.utils.AssetManager

/** Voices for the characters of the section being read, from the installed model ([BookVoiceAssign]).
 * The three role voices of multi-voice reading stay the narrator and the «прочие» (other men / women). */
object BookVoices {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    fun context(ctx: Context, position: BookMatcher.Position?): BookContext? {
        position ?: return null
        val pkg = runCatching { BookLibrary.get(ctx, position.book) }.getOrNull() ?: return null
        val cast = pkg.castOf(position.section)?.takeIf { it.characters.isNotEmpty() } ?: return null
        val available = AssetManager.russianVoices(ctx)
        val reserved = VoiceRole.entries.map { MultiVoiceSettings.selected(ctx, it) }.toSet()
        val key = "${position.book}/${pkg.sections.firstOrNull { it.id == position.section }?.cast}/${AssetManager.getModelType(ctx)}/$reserved"
        val voices = cache.getOrPut(key) { BookVoiceAssign.assign(cast, available, reserved) }
        return BookContext(position.book, position.section, cast, voices)
    }

    fun clear() = cache.clear()
}
