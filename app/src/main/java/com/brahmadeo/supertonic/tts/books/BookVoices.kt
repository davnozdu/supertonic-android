package com.brahmadeo.supertonic.tts.books

import android.content.Context
import com.brahmadeo.supertonic.tts.llm.MultiVoiceSettings
import com.brahmadeo.supertonic.tts.llm.VoiceRole
import com.brahmadeo.supertonic.tts.utils.AssetManager

/** Voices for the characters of the section being read, from the installed model ([BookVoiceAssign]).
 * The three role voices of multi-voice reading stay the narrator and the «прочие» (other men / women).
 * A voice chosen by hand on the books screen (per book, character and model) wins over the assignment. */
object BookVoices {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("book_voices", Context.MODE_PRIVATE)
    private fun key(ctx: Context, book: Long, character: String) = "$book/${AssetManager.getModelType(ctx)}/$character"

    fun manual(ctx: Context, book: Long, character: String): String? = prefs(ctx).getString(key(ctx, book, character), null)

    private fun roleKey(ctx: Context, book: Long, role: VoiceRole) = key(ctx, book, "@" + role.name.lowercase())

    /** The book's own narrator / «прочие» voice, or null when the global one from multi-voice settings is used. */
    fun ownRole(ctx: Context, book: Long, role: VoiceRole): String? =
        prefs(ctx).getString(roleKey(ctx, book, role), null)?.takeIf { it in AssetManager.russianVoices(ctx) }

    fun roleVoice(ctx: Context, book: Long, role: VoiceRole): String = ownRole(ctx, book, role) ?: MultiVoiceSettings.selected(ctx, role)

    /** [voice] null returns the role to the global voice. */
    fun chooseRole(ctx: Context, book: Long, role: VoiceRole, voice: String?) {
        prefs(ctx).edit().apply { if (voice == null) remove(roleKey(ctx, book, role)) else putString(roleKey(ctx, book, role), voice) }.apply()
        clear()
        com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }

    /** [voice] null returns the character to the automatic assignment. */
    fun choose(ctx: Context, book: Long, character: String, voice: String?) {
        prefs(ctx).edit().apply { if (voice == null) remove(key(ctx, book, character)) else putString(key(ctx, book, character), voice) }.apply()
        clear()
        // Paragraphs already prepared carry the old voice.
        com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }

    /** Character → voice for one set of characters (one section of a collection, or the whole novel). */
    fun voices(ctx: Context, book: Long, castIndex: Int, cast: BookPackage.Cast): Map<String, String> {
        val available = AssetManager.russianVoices(ctx)
        val reserved = VoiceRole.entries.map { roleVoice(ctx, book, it) }.toSet()
        return cache.getOrPut("$book/$castIndex/${AssetManager.getModelType(ctx)}/$reserved") {
            val assigned = BookVoiceAssign.assign(cast, available, reserved).toMutableMap()
            for (ch in cast.characters) manual(ctx, book, ch.id)?.takeIf { it in available }?.let { assigned[ch.id] = it }
            assigned
        }
    }

    fun context(ctx: Context, position: BookMatcher.Position?): BookContext? {
        position ?: return null
        val pkg = runCatching { BookLibrary.get(ctx, position.book) }.getOrNull() ?: return null
        // A section without characters still reads with the book's own narrator voice.
        val index = pkg.sections.firstOrNull { it.id == position.section }?.cast ?: -1
        val cast = pkg.casts.getOrNull(index) ?: BookPackage.Cast(emptyList(), emptyList(), emptyList())
        val roles = VoiceRole.entries.mapNotNull { role -> ownRole(ctx, position.book, role)?.let { role to it } }.toMap()
        return BookContext(position.book, position.section, cast, voices(ctx, position.book, index, cast), roles)
    }

    fun clear() = cache.clear()
}
