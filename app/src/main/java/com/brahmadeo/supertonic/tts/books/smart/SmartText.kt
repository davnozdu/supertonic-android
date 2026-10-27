package com.brahmadeo.supertonic.tts.books.smart

import com.brahmadeo.supertonic.tts.books.BookFingerprint
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** «Умная книга»: the text of a book by sections, for «Ранее в книге» / «Эта глава» / «Чем закончится».
 * Paragraphs are numbered through the whole book; a fingerprint points at its paragraph, so the place being read is
 * known to the paragraph. [summaries]: short content of a section (by section index), made once by the cloud LLM. */
data class SmartText(val title: String, val author: String, val sections: List<Section>, val summaries: Map<Int, String> = emptyMap()) {
    data class Section(val title: String, val paragraphs: List<String>)

    /** First paragraph (global number) of every section, and the total. */
    val starts: IntArray by lazy {
        IntArray(sections.size + 1).also { s -> sections.forEachIndexed { i, sec -> s[i + 1] = s[i] + sec.paragraphs.size } }
    }
    val paragraphs: Int get() = starts.last()

    fun sectionOf(paragraph: Int): Int {
        if (sections.isEmpty()) return 0
        val p = paragraph.coerceIn(0, paragraphs - 1)
        var lo = 0; var hi = sections.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (starts[mid] <= p) lo = mid else hi = mid - 1 }
        return lo
    }

    /** Text of [section], all of it or up to and including global paragraph [until]. */
    fun sectionText(section: Int, until: Int = Int.MAX_VALUE): String {
        val sec = sections.getOrNull(section) ?: return ""
        val count = if (until == Int.MAX_VALUE) sec.paragraphs.size else (until - starts[section] + 1).coerceIn(0, sec.paragraphs.size)
        return sec.paragraphs.take(count).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** Fingerprint → global paragraph. A sentence met in two paragraphs points nowhere (-1). */
    fun fingerprints(): Map<Long, Int> {
        val index = HashMap<Long, Int>()
        var p = 0
        for (sec in sections) for (text in sec.paragraphs) {
            for (fp in BookFingerprint.sentences(text)) { val old = index[fp]; index[fp] = if (old != null && old != p) -1 else p }
            p++
        }
        return index
    }

    /** The paragraph layout: summaries made for one division into sections do not fit another. */
    val layout: String get() = sections.joinToString(",") { it.paragraphs.size.toString() }

    fun toJson(): JSONObject = JSONObject().put("version", 1).put("title", title).put("author", author)
        .put("sections", JSONArray(sections.mapIndexed { i, s ->
            JSONObject().put("title", s.title).put("paragraphs", JSONArray(s.paragraphs)).apply { summaries[i]?.let { put("summary", it) } }
        }))

    companion object {
        const val MAX_PARAGRAPHS = 200_000
        const val MAX_CHARS = 20_000_000

        fun parse(o: JSONObject): SmartText {
            require(o.optInt("version", 1) == 1) { "Неизвестная версия текста книги" }
            val array = o.getJSONArray("sections")
            var paragraphs = 0; var chars = 0L
            val summaries = HashMap<Int, String>()
            val sections = (0 until array.length()).map { i ->
                val s = array.getJSONObject(i)
                val ps = s.getJSONArray("paragraphs")
                Section(s.optString("title"), (0 until ps.length()).map { ps.getString(it) }.also { list ->
                    paragraphs += list.size; chars += list.sumOf { it.length.toLong() }
                    require(paragraphs <= MAX_PARAGRAPHS && chars <= MAX_CHARS) { "Текст книги слишком велик" }
                }).also { s.optString("summary").takeIf { it.isNotBlank() }?.let { summaries[i] = it } }
            }
            return SmartText(o.optString("title"), o.optString("author"), sections, summaries)
        }

        /** content_sha256 of the `.mytts-book` format: SHA-256 of the paragraphs' letters, one per line. A book prepared
         * with voices and the same file found in the books folder get the same key. */
        fun contentKey(paragraphs: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            paragraphs.forEachIndexed { i, p ->
                if (i > 0) digest.update('\n'.code.toByte())
                digest.update(BookFingerprint.letters(p).toByteArray(Charsets.UTF_8))
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** From a book read by the preparation: sections in order, their paragraphs. */
        fun of(title: String, author: String, sectionTitles: List<String>, paragraphs: List<Pair<Int, String>>): SmartText {
            val bySection = List(sectionTitles.size) { mutableListOf<String>() }
            for ((section, text) in paragraphs) bySection.getOrNull(section)?.add(text)
            return SmartText(title, author, sectionTitles.mapIndexed { i, t -> Section(t, bySection[i]) })
        }
    }
}
