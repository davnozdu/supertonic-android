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

    @Volatile private var migrated = false
    private fun prefs(ctx: Context): android.content.SharedPreferences {
        val prefs = ctx.getSharedPreferences("book_voices", Context.MODE_PRIVATE)
        if (!migrated) synchronized(this) {
            if (!migrated) {
                // Before 5.0.4-beta28 the choices were stored under the row id, lost with a deleted and prepared again
                // book: move them to its text (content_sha256).
                val all = prefs.all
                val edit = prefs.edit()
                for ((id, sha) in runCatching { BookLibrary.contentShas(ctx) }.getOrDefault(emptyMap())) {
                    for ((k, v) in all) if (k.startsWith("$id/")) {
                        val target = "s$sha/" + k.substringAfter('/')
                        if (target !in all) when (v) { is String -> edit.putString(target, v); is Boolean -> edit.putBoolean(target, v) }
                        edit.remove(k)
                    }
                }
                edit.apply(); migrated = true
            }
        }
        return prefs
    }
    /** Settings of a book live under its text, not its row id: a regenerated or re-imported book keeps them. */
    private fun id(ctx: Context, book: Long) = BookLibrary.contentSha(ctx, book)?.let { "s$it" } ?: book.toString()
    private fun key(ctx: Context, book: Long, character: String) = "${id(ctx, book)}/${AssetManager.getModelType(ctx)}/$character"
    private fun roleKey(ctx: Context, book: Long, role: VoiceRole) = key(ctx, book, "@" + role.name.lowercase())

    private fun modelKey(ctx: Context, book: Long) = "${id(ctx, book)}/models"
    fun configured(ctx: Context, book: Long): Boolean = prefs(ctx).contains(modelKey(ctx, book))
    fun models(ctx: Context, book: Long): List<String> = prefs(ctx).getString(modelKey(ctx, book), null)?.split(',')
        ?.filter { it in BookVoiceRef.models }?.distinct()?.takeIf { it.isNotEmpty() }
        ?: listOf(AssetManager.getModelType(ctx)).filter { it in BookVoiceRef.models }
    fun available(ctx: Context, book: Long): List<String> = if (configured(ctx, book)) BookVoiceCatalog.voices(ctx, models(ctx, book)) else AssetManager.russianVoices(ctx)
    fun setModels(ctx: Context, book: Long, models: List<String>) {
        require(models.isNotEmpty() && models.all { BookVoiceCatalog.ready(ctx, it) })
        val edit = prefs(ctx).edit().putString(modelKey(ctx, book), models.distinct().joinToString(","))
        if (!configured(ctx, book)) for (role in VoiceRole.entries) {
            prefs(ctx).getString(roleKey(ctx, book, role), null)?.let { old ->
                edit.putString(mixKey(ctx, book, "@" + role.name.lowercase()), BookVoiceRef(AssetManager.getModelType(ctx), old).key)
            }
        }
        edit.apply()
        clear(); com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
        com.brahmadeo.supertonic.tts.SupertonicTTS.trimBookEngines(models.toSet())
    }
    private fun mixKey(ctx: Context, book: Long, character: String) = "${id(ctx, book)}/mix/$character"

    private fun bindingKey(ctx: Context, book: Long) = "${id(ctx, book)}/binding"
    fun binding(ctx: Context, book: Long): BookVoiceBinding = BookVoiceBinding.parse(prefs(ctx).getString(bindingKey(ctx, book), null))
    @Synchronized private fun fix(ctx: Context, book: Long, change: (BookVoiceBinding) -> BookVoiceBinding?) {
        change(binding(ctx, book))?.let { prefs(ctx).edit().putString(bindingKey(ctx, book), it.json()).apply() }
    }
    private fun fixedRole(ctx: Context, book: Long, role: VoiceRole): String? = adapt(ctx, book, binding(ctx, book).roles[role.name.lowercase()])
    private fun adapt(ctx: Context, book: Long, voice: String?): String? {
        voice ?: return null
        val value = if (configured(ctx, book) && BookVoiceRef.parse(voice) == null) BookVoiceRef(AssetManager.getModelType(ctx), voice).key else voice
        return value.takeIf { it in available(ctx, book) }
    }
    /** Explicit redistribution replaces character overrides and the fixed voices, preserving narrator/other role
     * choices made by hand; the new distribution is fixed again as it is read. */
    fun redistribute(ctx: Context, book: Long) {
        if (!configured(ctx, book)) setModels(ctx, book, models(ctx, book))
        val prefix = "${id(ctx, book)}/mix/"
        val edit = prefs(ctx).edit()
        for (key in prefs(ctx).all.keys) if (key.startsWith(prefix) && !key.substringAfter(prefix).startsWith('@')) edit.remove(key)
        edit.remove(bindingKey(ctx, book))
        edit.putBoolean("${id(ctx, book)}/redistributed", true).apply()
        clear(); com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }

    fun manual(ctx: Context, book: Long, character: String): String? = if (configured(ctx, book)) {
        adapt(ctx, book, prefs(ctx).getString(mixKey(ctx, book, character), null) ?: if (!prefs(ctx).getBoolean("${id(ctx, book)}/redistributed", false)) prefs(ctx).getString(key(ctx, book, character), null) else null)
    } else prefs(ctx).getString(key(ctx, book, character), null)

    /** The voice chosen by hand for the book's narrator / «прочие», or null (automatic). */
    fun ownRole(ctx: Context, book: Long, role: VoiceRole): String? =
        adapt(ctx, book, prefs(ctx).getString(if (configured(ctx, book)) mixKey(ctx, book, "@" + role.name.lowercase()) else roleKey(ctx, book, role), null))

    /** [voice] null returns the character to the automatic assignment. */
    fun choose(ctx: Context, book: Long, character: String, voice: String?) = save(ctx, if (configured(ctx, book)) mixKey(ctx, book, character) else key(ctx, book, character), voice)

    /** [voice] null returns the narrator / «прочие» to the automatic choice. */
    fun chooseRole(ctx: Context, book: Long, role: VoiceRole, voice: String?) = save(ctx, if (configured(ctx, book)) mixKey(ctx, book, "@" + role.name.lowercase()) else roleKey(ctx, book, role), voice)

    private fun save(ctx: Context, key: String, voice: String?) {
        prefs(ctx).edit().apply { if (voice == null && "/mix/" !in key) remove(key) else putString(key, voice ?: "") }.apply()
        clear()
        // Paragraphs already prepared carry the old voices.
        com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }

    /** The narrator of the book: chosen by hand, otherwise the voice it was first read with (the multi-voice narrator
     * voice then), fixed: a later change of the global narrator or a regenerated file does not move it. */
    fun author(ctx: Context, book: Long): String = ownRole(ctx, book, VoiceRole.AUTHOR) ?: fixedRole(ctx, book, VoiceRole.AUTHOR)
        ?: defaultRole(ctx, book, VoiceRole.AUTHOR).also { v -> fix(ctx, book) { it.withRole("author", v) } }
    private fun defaultRole(ctx: Context, book: Long, role: VoiceRole): String {
        adapt(ctx, book, MultiVoiceSettings.selected(ctx, role))?.let { return it }
        val model = models(ctx, book).firstOrNull { BookVoiceCatalog.ready(ctx, it) } ?: return MultiVoiceSettings.selected(ctx, role)
        val value = MultiVoiceSettings.selected(com.brahmadeo.supertonic.tts.utils.ModelContext(ctx, model), role)
        return if (configured(ctx, book)) BookVoiceRef(model, value).key else value
    }

    /** Voices of one group of characters (one story of a collection, or the whole novel even when its chapters
     * have separate casts): the same character keeps one voice in every chapter. */
    fun assignment(ctx: Context, book: Long, pkg: BookPackage, castIndex: Int): BookVoiceAssign.Assignment {
        val group = pkg.groupOf(castIndex)
        val cast = if (group >= 0) pkg.groupCast(group) else BookPackage.Cast(emptyList(), emptyList(), emptyList())
        val available = available(ctx, book)
        val author = author(ctx, book)
        val ownMale = ownRole(ctx, book, VoiceRole.MALE) ?: fixedRole(ctx, book, VoiceRole.MALE)
        val ownFemale = ownRole(ctx, book, VoiceRole.FEMALE) ?: fixedRole(ctx, book, VoiceRole.FEMALE)
        return cache.getOrPut("$book/g$group/${available.hashCode()}/$author/$ownMale/$ownFemale") {
            // Fixed voices first (as if chosen by hand): a character keeps the voice it was read with.
            val fixed = binding(ctx, book).resolve(cast.characters)
            val manual = cast.characters.mapNotNull { ch -> (manual(ctx, book, ch.id) ?: fixed[ch.id]?.let { adapt(ctx, book, it) }
                ?.takeIf { it != author && VoiceGenderSettings.gender(ctx, it) == ch.gender })?.let { ch.id to it } }.toMap()
            val effective = if (configured(ctx, book)) cast.copy(characters = cast.characters.map { it.copy(voiceHint = null) }) else cast
            BookVoiceAssign.assign(effective, available, author, manual, ownMale, ownFemale, available.associateWith { VoiceGenderSettings.gender(ctx, it) })
                .also { a -> fix(ctx, book) { b ->
                    var next = b.with(cast.characters, a.characters) { it in available } ?: b
                    a.male?.let { v -> next = next.withRole("male", v) ?: next }
                    a.female?.let { v -> next = next.withRole("female", v) ?: next }
                    next.takeIf { it != b }
                } }
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
        val assignment = assignment(ctx, position.book, pkg, index)
        val roles = VoiceRole.entries.associateWith { roleVoice(ctx, position.book, it, assignment) }
        return BookContext(position.book, position.section, cast, assignment.characters, roles, firstPerson(ctx, position.book))
    }

    /** First-person narration for this book: true / false chosen on the books screen, null — the global setting. */
    fun firstPerson(ctx: Context, book: Long): Boolean? =
        prefs(ctx).getString("${id(ctx, book)}/first_person", null)?.let { it == "1" }
    fun setFirstPerson(ctx: Context, book: Long, value: Boolean?) {
        prefs(ctx).edit().apply { if (value == null) remove("${id(ctx, book)}/first_person") else putString("${id(ctx, book)}/first_person", if (value) "1" else "0") }.apply()
        clear()
        // Roles already prepared were marked with the old setting.
        com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }

    fun clear() = cache.clear()
}
