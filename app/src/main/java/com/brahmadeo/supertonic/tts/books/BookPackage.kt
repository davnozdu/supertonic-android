package com.brahmadeo.supertonic.tts.books

import org.json.JSONArray
import org.json.JSONObject

/** A prepared book (`.mytts-book` v1): characters per section and sentence fingerprints, no book text.
 * Made by tools/characters/book_characters.py export; shared between users as a file. */
data class BookPackage(
    val title: String,
    val author: String,
    val fileSha256: String,
    val contentSha256: String,
    val scope: String,
    val sections: List<Section>,
    val casts: List<Cast>,
    val fingerprints: Map<String, List<Long>>,
) {
    data class Section(val id: String, val title: String, val cast: Int?)
    data class Cast(val sections: List<String>, val characters: List<Character>, val other: List<String>)
    data class Character(val id: String, val name: String, val gender: String, val speaker: Int, val mentions: Int,
                         val forms: List<String>, val voiceHint: String?)

    fun castOf(section: String?): Cast? = sections.firstOrNull { it.id == section }?.cast?.let { casts.getOrNull(it) }

    /** Casts that share characters form one group. A novel prepared by chapters has several casts with the same
     * characters (only who a title like «генерал» refers to differs between chapters): one voice per character
     * and one list in the settings for the whole group. Stories of a collection have their own characters and
     * stay separate groups. */
    val groups: List<List<Int>> by lazy {
        val parent = IntArray(casts.size) { it }
        fun root(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
        val owner = HashMap<String, Int>()
        casts.forEachIndexed { i, cast ->
            for (ch in cast.characters) owner.put(ch.id, i)?.let { j -> parent[root(i)] = root(j) }
        }
        casts.indices.groupBy { root(it) }.values.toList()
    }

    fun groupOf(castIndex: Int): Int = groups.indexOfFirst { castIndex in it }

    /** All characters of a group once: replies and mentions summed over its chapters, forms joined. */
    fun groupCast(group: Int): Cast {
        val members = groups.getOrNull(group).orEmpty().map { casts[it] }
        val merged = LinkedHashMap<String, Character>()
        for (cast in members) for (ch in cast.characters) {
            val seen = merged[ch.id]
            merged[ch.id] = if (seen == null) ch else seen.copy(
                speaker = seen.speaker + ch.speaker, mentions = seen.mentions + ch.mentions,
                forms = (seen.forms + ch.forms).distinct(), voiceHint = seen.voiceHint ?: ch.voiceHint)
        }
        return Cast(members.flatMap { it.sections }, merged.values.toList(), members.flatMap { it.other }.distinct())
    }

    companion object {
        const val MAX_BYTES = 32L * 1024 * 1024
        private const val MAX_FINGERPRINTS = 1_000_000
        private val ID = Regex("[a-z0-9_.]{1,80}")

        /** Strict: anything malformed is rejected as a whole, nothing partial reaches reading. */
        fun parse(json: String): BookPackage {
            val o = JSONObject(json)
            require(o.optString("format") == "mytts-book") { "Это не файл подготовленной книги MyTTS" }
            require(o.optInt("version") == 1) { "Неподдерживаемая версия файла: ${o.opt("version")}" }
            val fp = o.getJSONObject("fingerprint")
            require(fp.getString("algorithm") == BookFingerprint.ALGORITHM && fp.getInt("min_letters") == BookFingerprint.MIN_LETTERS) {
                "Неизвестный способ отпечатков: ${fp.optString("algorithm")}"
            }
            val book = o.getJSONObject("book")
            val sections = o.getJSONArray("sections").objects().map {
                Section(it.getString("id"), it.optString("title"), if (it.isNull("cast")) null else it.getInt("cast"))
            }
            require(sections.isNotEmpty() && sections.map { it.id }.distinct().size == sections.size) { "Разделы книги повторяются или отсутствуют" }
            val casts = o.getJSONArray("casts").objects().map { c ->
                val characters = c.getJSONArray("characters").objects().map { ch ->
                    val id = ch.getString("id")
                    require(ID.matches(id)) { "Некорректный идентификатор персонажа: $id" }
                    val gender = ch.getString("gender")
                    require(gender in listOf("m", "f", "?")) { "Некорректный пол персонажа $id" }
                    Character(id, ch.getString("name"), gender, ch.optInt("speaker"), ch.optInt("mentions"),
                        ch.optJSONArray("forms")?.strings().orEmpty(), ch.optString("voice_hint").ifBlank { null })
                }
                require(characters.map { it.id }.distinct().size == characters.size) { "Персонажи повторяются" }
                Cast(c.getJSONArray("sections").strings(), characters, c.optJSONArray("other")?.strings().orEmpty())
            }
            require(sections.all { it.cast == null || it.cast in casts.indices }) { "Раздел ссылается на несуществующий набор персонажей" }
            val known = sections.map { it.id }.toSet()
            val prints = o.getJSONObject("fingerprints")
            val fingerprints = prints.keys().asSequence().associateWith { sid ->
                require(sid in known) { "Отпечатки несуществующего раздела $sid" }
                prints.getJSONArray(sid).strings().map(BookFingerprint::parse)
            }
            require(fingerprints.values.sumOf { it.size } in 1..MAX_FINGERPRINTS) { "В файле нет отпечатков текста" }
            return BookPackage(book.getString("title"), book.optString("author"), book.optString("file_sha256"),
                book.getString("content_sha256"), o.optString("scope", "book"), sections, casts, fingerprints)
        }

        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    }
}
