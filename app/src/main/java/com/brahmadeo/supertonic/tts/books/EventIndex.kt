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
    fun hit(text: String, before: List<String>): Hit? = hitAll(text, before, 1).firstOrNull()

    /** Every event of [text], in the order they sound, each with the word it starts at; at most [limit]. An utterance
     * of a reader often holds several described actions, and only the first one used to sound. The same sound is not
     * repeated inside one utterance: its clip lasts 8 s and the repeats would overlap. */
    fun hitAll(text: String, before: List<String>, limit: Int = MAX_PER_TEXT): List<Hit> {
        if (keys.isEmpty() || limit <= 0) return emptyList()
        val window = ArrayDeque(before.takeLast(WINDOW - 1))
        val out = ArrayList<Hit>(minOf(limit, 4))
        for ((n, w) in ReplyIndex.words(text).withIndex()) {
            window.addLast(w); while (window.size > WINDOW) window.removeFirst()
            if (window.size < WINDOW) continue
            val i = keys.binarySearch(ReplyIndex.key(window))
            if (i < 0) continue
            val event = events.getOrNull(values[i].toInt()) ?: continue
            if (out.any { it.event.id == event.id }) continue
            out += Hit(event, n)
            if (out.size >= limit) break
        }
        return out
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
        /** Event sounds an utterance may bring: a dense paragraph may describe six or seven actions, and every one of
         * them is in the index; the player spaces them out so they do not pile up. */
        const val MAX_PER_TEXT = 8
        /** The chapter transition («Переходы между главами»): a book-level sound of its own. */
        const val CHAPTER_ID = "e0"
        const val CHAPTER_SOUND = "a soft page turn followed by a gentle low chime, quiet and warm"
        private val ID = Regex("e[0-9]{1,4}")

        /** Where [quote] starts in [words], tolerating a re-worded or re-cut answer: the whole quote, then its first three
         * words, then any three words of it in order. A model rewrites quotes by a word or two too often to lose the
         * event over it, and the exact position inside the quote is not needed — the index key is a window ending in it. */
        fun anchorStart(words: List<String>, quote: List<String>): Int? {
            if (words.isEmpty() || quote.isEmpty()) return null
            find(words, quote)?.let { return it }
            if (quote.size >= 2) find(words, quote.take(minOf(3, quote.size)))?.let { return it }
            if (quote.size >= 3) for (s in 1..quote.size - 3) find(words, quote.subList(s, s + 3))?.let { return it }
            return null
        }

        private fun find(words: List<String>, part: List<String>): Int? {
            if (part.isEmpty() || part.size > words.size) return null
            for (s in 0..words.size - part.size) {
                var same = true
                for (i in part.indices) if (words[s + i] != part[i]) { same = false; break }
                if (same) return s
            }
            return null
        }

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
                    val start = anchorStart(words, q) ?: continue
                    // The first window that ends inside the quote (its first words with the ones before). Two actions of
                    // one sentence can end in the same window; then the neighbouring window keeps both findable.
                    val first = p + start + minOf(WINDOW - 1, q.size - 1)
                    for (delta in intArrayOf(0, 1, 2, 3, -1, -2)) {
                        val end = first + delta
                        if (end - WINDOW + 1 < 0 || end >= p + words.size) continue
                        if (entries.putIfAbsent(ReplyIndex.key(full.subList(end - WINDOW + 1, end + 1)), event) == null) break
                    }
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
