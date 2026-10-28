package com.brahmadeo.supertonic.tts.books

/** «Звуки событий»: audible moments the book describes (a door slams, a shot, a horse neighs), each with a short
 * sound. An occurrence is found like [ReplyIndex], by the window of [WINDOW] words where its quote starts, so any cut
 * of the text works; [hit] also says how many words into the reader's text it is (the sound waits for the voice).
 * The same sound in several places is one [Event]. Stored as sorted 8-byte keys + 2-byte event numbers, base64. */
class EventIndex private constructor(private val keys: LongArray, private val values: ShortArray, val events: List<Event>) {
    /** [id]: e<N> (the file e<N>.m4a); [sound]: the English prompt of the one-shot sound. */
    data class Event(val id: String, val sound: String)
    data class Hit(val event: Event, val word: Int)

    val size get() = keys.size

    /** The first event in [text] after the words [before], or null. */
    fun hit(text: String, before: List<String>): Hit? {
        if (keys.isEmpty()) return null
        val window = ArrayDeque(before.takeLast(WINDOW - 1))
        for ((n, w) in ReplyIndex.words(text).withIndex()) {
            window.addLast(w); while (window.size > WINDOW) window.removeFirst()
            if (window.size < WINDOW) continue
            val i = keys.binarySearch(ReplyIndex.key(window))
            if (i >= 0) return events.getOrNull(values[i].toInt())?.let { Hit(it, n) }
        }
        return null
    }

    fun toJson(): org.json.JSONObject {
        val bytes = java.nio.ByteBuffer.allocate(keys.size * 10)
        for (i in keys.indices) { bytes.putLong(keys[i]); bytes.putShort(values[i]) }
        return org.json.JSONObject()
            .put("events", org.json.JSONArray(events.map { org.json.JSONObject().put("id", it.id).put("sound", it.sound) }))
            .put("index", java.util.Base64.getEncoder().encodeToString(bytes.array()))
    }

    companion object {
        const val WINDOW = ReplyIndex.WINDOW
        /** The chapter transition («Переходы между главами»): a book-level sound of its own. */
        const val CHAPTER_ID = "e0"
        const val CHAPTER_SOUND = "a soft page turn followed by a gentle low chime, quiet and warm"
        private val ID = Regex("e[0-9]{1,4}")

        /** Feed every paragraph in reading order with its events: (quote, event number). */
        class Builder(private val events: List<Event>) {
            private val before = ArrayDeque<String>()
            private val entries = HashMap<Long, Int>()
            fun add(paragraph: String, marks: List<Pair<String, Int>>) {
                val words = ReplyIndex.words(paragraph)
                val full = before.toList() + words
                val p = before.size
                for ((quote, event) in marks) {
                    val q = ReplyIndex.words(quote)
                    if (q.isEmpty()) continue
                    val start = (0..words.size - q.size).firstOrNull { s -> q.indices.all { words[s + it] == q[it] } } ?: continue
                    // The first window that ends inside the quote (its first words with the ones before).
                    val end = p + start + minOf(WINDOW - 1, q.size - 1)
                    if (end - WINDOW + 1 < 0) continue
                    entries.putIfAbsent(ReplyIndex.key(full.subList(end - WINDOW + 1, end + 1)), event)
                }
                for (w in words) { before.addLast(w); while (before.size > WINDOW - 1) before.removeFirst() }
            }
            fun build(): EventIndex {
                val sorted = entries.entries.sortedBy { it.key }
                return EventIndex(LongArray(sorted.size) { sorted[it].key }, ShortArray(sorted.size) { sorted[it].value.toShort() }, events)
            }
        }

        fun parse(o: org.json.JSONObject): EventIndex {
            val list = o.getJSONArray("events")
            val events = (0 until list.length()).map { list.getJSONObject(it) }.map {
                val id = it.getString("id")
                require(ID.matches(id) && id != CHAPTER_ID) { "Некорректный идентификатор события: $id" }
                Event(id, it.getString("sound"))
            }
            require(events.size <= Short.MAX_VALUE && events.map { it.id }.distinct().size == events.size) { "События повторяются" }
            val bytes = java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(o.getString("index")))
            require(bytes.remaining() % 10 == 0) { "Некорректная разметка событий" }
            val n = bytes.remaining() / 10
            val keys = LongArray(n); val values = ShortArray(n)
            for (i in 0 until n) {
                keys[i] = bytes.long; values[i] = bytes.short
                require(values[i] in 0 until events.size) { "Некорректная разметка событий" }
                require(i == 0 || keys[i] > keys[i - 1]) { "Разметка событий не упорядочена" }
            }
            return EventIndex(keys, values, events)
        }

        val EMPTY = EventIndex(LongArray(0), ShortArray(0), emptyList())
    }
}
