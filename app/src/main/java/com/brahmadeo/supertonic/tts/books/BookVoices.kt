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

    private fun modelKey(book: Long) = "$book/models"
    fun configured(ctx: Context, book: Long): Boolean = prefs(ctx).contains(modelKey(book))
    fun models(ctx: Context, book: Long): List<String> = prefs(ctx).getString(modelKey(book), null)?.split(',')
        ?.filter { it in BookVoiceRef.models }?.distinct()?.takeIf { it.isNotEmpty() }
        ?: listOf(AssetManager.getModelType(ctx)).filter { it in BookVoiceRef.models }
    fun available(ctx: Context, book: Long): List<String> = if (configured(ctx, book)) BookVoiceCatalog.voices(ctx, models(ctx, book)) else AssetManager.russianVoices(ctx)
    fun setModels(ctx: Context, book: Long, models: List<String>) {
        require(models.isNotEmpty() && models.all { BookVoiceCatalog.ready(ctx, it) })
        val edit = prefs(ctx).edit().putString(modelKey(book), models.distinct().joinToString(","))
        if (!configured(ctx, book)) for (role in VoiceRole.entries) {
            prefs(ctx).getString(roleKey(ctx, book, role), null)?.let { old ->
                edit.putString(mixKey(book, "@" + role.name.lowercase()), BookVoiceRef(AssetManager.getModelType(ctx), old).key)
            }
        }
        edit.apply()
        clear(); com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
        com.brahmadeo.supertonic.tts.SupertonicTTS.trimBookEngines(models.toSet())
    }
    private fun mixKey(book: Long, character: String) = "$book/mix/$character"
    private fun adapt(ctx: Context, book: Long, voice: String?): String? {
        voice ?: return null
        val value = if (configured(ctx, book) && BookVoiceRef.parse(voice) == null) BookVoiceRef(AssetManager.getModelType(ctx), voice).key else voice
        return value.takeIf { it in available(ctx, book) }
    }
    /** Explicit redistribution replaces character overrides, preserving narrator/other role choices. */
    fun redistribute(ctx: Context, book: Long) {
        if (!configured(ctx, book)) setModels(ctx, book, models(ctx, book))
        val prefix = "$book/mix/"
        val edit = prefs(ctx).edit()
        for (key in prefs(ctx).all.keys) if (key.startsWith(prefix) && !key.substringAfter(prefix).startsWith('@')) edit.remove(key)
        edit.putBoolean("$book/redistributed", true).apply()
        clear(); com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }

    fun manual(ctx: Context, book: Long, character: String): String? = if (configured(ctx, book)) {
        adapt(ctx, book, prefs(ctx).getString(mixKey(book, character), null) ?: if (!prefs(ctx).getBoolean("$book/redistributed", false)) prefs(ctx).getString(key(ctx, book, character), null) else null)
    } else prefs(ctx).getString(key(ctx, book, character), null)

    /** The voice chosen by hand for the book's narrator / «прочие», or null (automatic). */
    fun ownRole(ctx: Context, book: Long, role: VoiceRole): String? =
        adapt(ctx, book, prefs(ctx).getString(if (configured(ctx, book)) mixKey(book, "@" + role.name.lowercase()) else roleKey(ctx, book, role), null))

    /** [voice] null returns the character to the automatic assignment. */
    fun choose(ctx: Context, book: Long, character: String, voice: String?) = save(ctx, if (configured(ctx, book)) mixKey(book, character) else key(ctx, book, character), voice)

    /** [voice] null returns the narrator / «прочие» to the automatic choice. */
    fun chooseRole(ctx: Context, book: Long, role: VoiceRole, voice: String?) = save(ctx, if (configured(ctx, book)) mixKey(book, "@" + role.name.lowercase()) else roleKey(ctx, book, role), voice)

    private fun save(ctx: Context, key: String, voice: String?) {
        prefs(ctx).edit().apply { if (voice == null && "/mix/" !in key) remove(key) else putString(key, voice ?: "") }.apply()
        clear()
        // Paragraphs already prepared carry the old voices.
        com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }

    /** The narrator of the book: chosen by hand, otherwise the multi-voice narrator voice. */
    fun author(ctx: Context, book: Long): String = ownRole(ctx, book, VoiceRole.AUTHOR) ?: defaultRole(ctx, book, VoiceRole.AUTHOR)
    private fun defaultRole(ctx: Context, book: Long, role: VoiceRole): String {
        adapt(ctx, book, MultiVoiceSettings.selected(ctx, role))?.let { return it }
        val model = models(ctx, book).firstOrNull { BookVoiceCatalog.ready(ctx, it) } ?: return MultiVoiceSettings.selected(ctx, role)
        val value = MultiVoiceSettings.selected(com.brahmadeo.supertonic.tts.utils.ModelContext(ctx, model), role)
        return if (configured(ctx, book)) BookVoiceRef(model, value).key else value
    }

    /** Voices of one set of characters (one story of a collection, or the whole novel). */
    fun assignment(ctx: Context, book: Long, castIndex: Int, cast: BookPackage.Cast): BookVoiceAssign.Assignment {
        val available = available(ctx, book)
        val author = author(ctx, book)
        val ownMale = ownRole(ctx, book, VoiceRole.MALE)
        val ownFemale = ownRole(ctx, book, VoiceRole.FEMALE)
        return cache.getOrPut("$book/$castIndex/${available.hashCode()}/$author/$ownMale/$ownFemale") {
            val manual = cast.characters.mapNotNull { ch -> manual(ctx, book, ch.id)?.let { ch.id to it } }.toMap()
            val effective = if (configured(ctx, book)) cast.copy(characters = cast.characters.map { it.copy(voiceHint = null) }) else cast
            BookVoiceAssign.assign(effective, available, author, manual, ownMale, ownFemale, available.associateWith { VoiceGenderSettings.gender(ctx, it) })
        }
    }

    /** Narrator / «прочие» voice of the book for [role]; with no voice left, the multi-voice one. */
    fun roleVoice(ctx: Context, book: Long, role: VoiceRole, assignment: BookVoiceAssign.Assignment?): String = when (role) {
        VoiceRole.AUTHOR -> author(ctx, book)
        VoiceRole.MALE -> assignment?.male
        VoiceRole.FEMALE -> assignment?.female
    } ?: defaultRole(ctx, book, role)

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
