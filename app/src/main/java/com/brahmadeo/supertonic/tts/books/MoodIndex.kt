package com.brahmadeo.supertonic.tts.books

/** «Музыка по настроению»: the mood of each part of a prepared book, named freely by the LLM for this book (a horror
 * novel gets «гнетущий ужас», a romance its own) with an English prompt for the music generator; one book keeps a
 * few ([MAX] at most). Found by word windows like [SceneIndex] (every second word); [sections] gives one mood per
 * chapter (the one most of it has) for «по главам». Stored as sorted 8-byte keys + 2-byte mood numbers, base64. */
class MoodIndex private constructor(private val keys: LongArray, private val values: ShortArray, val moods: List<Mood>,
                                    val sections: Map<String, Int>) {
    /** [id]: m<N> (files m<N>_<k>.m4a); [name]: the mood in Russian; [music]: the English prompt. */
    data class Mood(val id: String, val name: String, val music: String)

    /** The mood of [text] after the words [before] (most words decide), or null when no word of it is known. */
    fun find(text: String, before: List<String>): Mood? {
        if (keys.isEmpty()) return null
        val window = ArrayDeque(before.takeLast(WINDOW - 1))
        val votes = HashMap<Int, Int>()
        for (w in ReplyIndex.words(text)) {
            window.addLast(w); while (window.size > WINDOW) window.removeFirst()
            if (window.size < WINDOW) continue
            val i = keys.binarySearch(ReplyIndex.key(window))
            if (i >= 0) votes.merge(values[i].toInt(), 1, Int::plus)
        }
        return votes.maxByOrNull { it.value }?.key?.let { moods.getOrNull(it) }
    }

    fun ofSection(section: String): Mood? = sections[section]?.let { moods.getOrNull(it) }

    fun toJson(): org.json.JSONObject {
        val bytes = java.nio.ByteBuffer.allocate(keys.size * 10)
        for (i in keys.indices) { bytes.putLong(keys[i]); bytes.putShort(values[i]) }
        return org.json.JSONObject()
            .put("moods", org.json.JSONArray(moods.map { org.json.JSONObject().put("id", it.id).put("name", it.name).put("music", it.music) }))
            .put("sections", org.json.JSONObject(sections as Map<*, *>))
            .put("index", java.util.Base64.getEncoder().encodeToString(bytes.array()))
    }

    companion object {
        const val WINDOW = ReplyIndex.WINDOW
        const val MAX = 12
        private val ID = Regex("m[0-9]{1,3}")

        /** Feed every paragraph in reading order with its section id and mood number (null: none known). */
        class Builder(private val moods: List<Mood>) {
            private val window = ArrayDeque<String>()
            private val entries = HashMap<Long, Int>()
            private val clash = HashSet<Long>()
            private val perSection = LinkedHashMap<String, HashMap<Int, Int>>()
            private var word = 0
            fun add(paragraph: String, section: String, mood: Int?) {
                val words = ReplyIndex.words(paragraph)
                if (mood != null) perSection.getOrPut(section) { HashMap() }.merge(mood, words.size, Int::plus)
                for (w in words) {
                    window.addLast(w); while (window.size > WINDOW) window.removeFirst()
                    if (word++ % 2 != 0 || window.size < WINDOW || mood == null) continue
                    val k = ReplyIndex.key(window)
                    if (k in clash) continue
                    val old = entries.putIfAbsent(k, mood)
                    if (old != null && old != mood) { entries.remove(k); clash += k }
                }
            }
            fun build(): MoodIndex {
                val sorted = entries.entries.sortedBy { it.key }
                val sections = perSection.mapValues { (_, counts) -> counts.maxBy { it.value }.key }
                return MoodIndex(LongArray(sorted.size) { sorted[it].key }, ShortArray(sorted.size) { sorted[it].value.toShort() }, moods, sections)
            }
        }

        fun parse(o: org.json.JSONObject): MoodIndex {
            val list = o.getJSONArray("moods")
            val moods = (0 until list.length()).map { list.getJSONObject(it) }.map {
                val id = it.getString("id")
                require(ID.matches(id)) { "Некорректный идентификатор настроения: $id" }
                Mood(id, it.getString("name"), it.getString("music"))
            }
            require(moods.size <= MAX && moods.map { it.id }.distinct().size == moods.size) { "Настроения повторяются" }
            val s = o.optJSONObject("sections") ?: org.json.JSONObject()
            val sections = s.keys().asSequence().associateWith { s.getInt(it) }.filterValues { it in moods.indices }
            val bytes = java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(o.getString("index")))
            require(bytes.remaining() % 10 == 0) { "Некорректная разметка настроений" }
            val n = bytes.remaining() / 10
            val keys = LongArray(n); val values = ShortArray(n)
            for (i in 0 until n) {
                keys[i] = bytes.long; values[i] = bytes.short
                require(values[i] in 0 until moods.size) { "Некорректная разметка настроений" }
                require(i == 0 || keys[i] > keys[i - 1]) { "Разметка настроений не упорядочена" }
            }
            return MoodIndex(keys, values, moods, sections)
        }

        val EMPTY = MoodIndex(LongArray(0), ShortArray(0), emptyList(), emptyMap())
    }
}
