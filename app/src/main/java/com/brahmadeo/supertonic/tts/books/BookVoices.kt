package com.brahmadeo.supertonic.tts.books

import android.content.Context
import com.brahmadeo.supertonic.tts.llm.MultiVoiceSettings
import com.brahmadeo.supertonic.tts.llm.VoiceRole
import com.brahmadeo.supertonic.tts.utils.AssetManager

/** Voices of a prepared book from the installed model ([BookVoiceAssign]): the narrator first (the multi-voice
 * narrator voice, it reads most of the text), then main characters, then the «прочие» from what is left.
 * Any of them can be chosen by hand on the books screen (per book and model); that choice wins. */
object BookVoices {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, BookVoiceAssign.Assignment>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("book_voices", Context.MODE_PRIVATE)
    private fun key(ctx: Context, book: Long, character: String) = "$book/${AssetManager.getModelType(ctx)}/$character"
    private fun roleKey(ctx: Context, book: Long, role: VoiceRole) = key(ctx, book, "@" + role.name.lowercase())

    fun manual(ctx: Context, book: Long, character: String): String? = prefs(ctx).getString(key(ctx, book, character), null)

    /** The voice chosen by hand for the book's narrator / «прочие», or null (automatic). */
    fun ownRole(ctx: Context, book: Long, role: VoiceRole): String? =
        prefs(ctx).getString(roleKey(ctx, book, role), null)?.takeIf { it in AssetManager.russianVoices(ctx) }

    /** [voice] null returns the character to the automatic assignment. */
    fun choose(ctx: Context, book: Long, character: String, voice: String?) = save(ctx, key(ctx, book, character), voice)

    /** [voice] null returns the narrator / «прочие» to the automatic choice. */
    fun chooseRole(ctx: Context, book: Long, role: VoiceRole, voice: String?) = save(ctx, roleKey(ctx, book, role), voice)

    private fun save(ctx: Context, key: String, voice: String?) {
        prefs(ctx).edit().apply { if (voice == null) remove(key) else putString(key, voice) }.apply()
        clear()
        // Paragraphs already prepared carry the old voices.
        com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }

    /** The narrator of the book: chosen by hand, otherwise the multi-voice narrator voice. */
    fun author(ctx: Context, book: Long): String = ownRole(ctx, book, VoiceRole.AUTHOR) ?: MultiVoiceSettings.selected(ctx, VoiceRole.AUTHOR)

    /** Voices of one set of characters (one story of a collection, or the whole novel). */
    fun assignment(ctx: Context, book: Long, castIndex: Int, cast: BookPackage.Cast): BookVoiceAssign.Assignment {
        val available = AssetManager.russianVoices(ctx)
        val author = author(ctx, book)
        val ownMale = ownRole(ctx, book, VoiceRole.MALE)
        val ownFemale = ownRole(ctx, book, VoiceRole.FEMALE)
        return cache.getOrPut("$book/$castIndex/${AssetManager.getModelType(ctx)}/$author/$ownMale/$ownFemale") {
            val manual = cast.characters.mapNotNull { ch -> manual(ctx, book, ch.id)?.let { ch.id to it } }.toMap()
            BookVoiceAssign.assign(cast, available, author, manual, ownMale, ownFemale)
        }
    }

    /** Narrator / «прочие» voice of the book for [role]; with no voice left, the multi-voice one. */
    fun roleVoice(ctx: Context, book: Long, role: VoiceRole, assignment: BookVoiceAssign.Assignment?): String = when (role) {
        VoiceRole.AUTHOR -> author(ctx, book)
        VoiceRole.MALE -> assignment?.male
        VoiceRole.FEMALE -> assignment?.female
    } ?: MultiVoiceSettings.selected(ctx, role)

    fun context(ctx: Context, position: BookMatcher.Position?): BookContext? {
        position ?: return null
        val pkg = runCatching { BookLibrary.get(ctx, position.book) }.getOrNull() ?: return null
        // A section without characters still reads with the book's narrator voice.
        val index = pkg.sections.firstOrNull { it.id == position.section }?.cast ?: -1
        val cast = pkg.casts.getOrNull(index) ?: BookPackage.Cast(emptyList(), emptyList(), emptyList())
        val assignment = assignment(ctx, position.book, index, cast)
        val roles = VoiceRole.entries.associateWith { roleVoice(ctx, position.book, it, assignment) }
        return BookContext(position.book, position.section, cast, assignment.characters, roles)
    }

    fun clear() = cache.clear()
}
