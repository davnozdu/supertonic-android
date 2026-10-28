package com.brahmadeo.supertonic.tts.books

/** Where each part of a prepared book takes place («Фоновые звуки локаций»), so the reader's text gets its location
 * without the LLM. Like [ReplyIndex], by word: a word is found by its letters and the [WINDOW] − 1 words before it
 * (across paragraphs), so any cut of the text works (Moon+ by paragraph, KOReader by sentence). Only every second
 * word is stored (half the size; a sentence of four words still has a hit). Text outside a recognised scene is not in
 * the index: there the background music plays. Stored as sorted 8-byte keys + 2-byte location numbers, base64. */
class SceneIndex private constructor(private val keys: LongArray, private val values: ShortArray, val locations: List<Location>) {
    /** [sound]: the English prompt for the ambience generator; [paragraphs]: how much of the book is there. */
    data class Location(val id: String, val name: String, val sound: String, val paragraphs: Int)

    val size get() = keys.size

    /** The location of [text] after the words [before] (most words decide), or null outside a scene. */
    fun resolve(text: String, before: List<String>): Location? {
        if (keys.isEmpty()) return null
        val window = ArrayDeque(before.takeLast(WINDOW - 1))
        val votes = HashMap<Int, Int>()
        for (w in ReplyIndex.words(text)) {
            window.addLast(w); while (window.size > WINDOW) window.removeFirst()
            if (window.size < WINDOW) continue
            val i = keys.binarySearch(ReplyIndex.key(window))
            if (i >= 0) votes.merge(values[i].toInt(), 1, Int::plus)
        }
        return votes.maxByOrNull { it.value }?.key?.let { locations.getOrNull(it) }
    }

    fun toJson(): org.json.JSONObject {
        val bytes = java.nio.ByteBuffer.allocate(keys.size * 10)
        for (i in keys.indices) { bytes.putLong(keys[i]); bytes.putShort(values[i]) }
        return org.json.JSONObject().put("algorithm", ALGORITHM)
            .put("locations", org.json.JSONArray(locations.map {
                org.json.JSONObject().put("id", it.id).put("name", it.name).put("sound", it.sound).put("paragraphs", it.paragraphs)
            }))
            .put("index", java.util.Base64.getEncoder().encodeToString(bytes.array()))
    }

    companion object {
        const val ALGORITHM = "words4-even-fnv1a64"
        const val WINDOW = ReplyIndex.WINDOW
        private val ID = Regex("[a-z0-9_]{1,40}")

        /** Feed every paragraph of the book in reading order with its location number (null: no scene). */
        class Builder(private val locations: List<Location>) {
            private val window = ArrayDeque<String>()
            private val entries = HashMap<Long, Int>()
            private val clash = HashSet<Long>()
            private var word = 0
            fun add(paragraph: String, location: Int?) {
                for (w in ReplyIndex.words(paragraph)) {
                    window.addLast(w); while (window.size > WINDOW) window.removeFirst()
                    if (word++ % 2 != 0 || window.size < WINDOW || location == null) continue
                    val k = ReplyIndex.key(window)
                    if (k in clash) continue
                    val old = entries.putIfAbsent(k, location)
                    if (old != null && old != location) { entries.remove(k); clash += k }
                }
            }
            fun build(): SceneIndex {
                val sorted = entries.entries.sortedBy { it.key }
                return SceneIndex(LongArray(sorted.size) { sorted[it].key }, ShortArray(sorted.size) { sorted[it].value.toShort() }, locations)
            }
        }

        fun parse(o: org.json.JSONObject): SceneIndex {
            require(o.getString("algorithm") == ALGORITHM) { "Неизвестный способ разметки локаций: ${o.optString("algorithm")}" }
            val list = o.getJSONArray("locations")
            val locations = (0 until list.length()).map { list.getJSONObject(it) }.map {
                val id = it.getString("id")
                require(ID.matches(id)) { "Некорректный идентификатор локации: $id" }
                Location(id, it.getString("name"), it.getString("sound"), it.optInt("paragraphs"))
            }
            require(locations.size <= Short.MAX_VALUE && locations.map { it.id }.distinct().size == locations.size) { "Локации повторяются" }
            val bytes = java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(o.getString("index")))
            require(bytes.remaining() % 10 == 0) { "Некорректная разметка локаций" }
            val n = bytes.remaining() / 10
            val keys = LongArray(n); val values = ShortArray(n)
            for (i in 0 until n) {
                keys[i] = bytes.long; values[i] = bytes.short
                require(values[i] in 0 until locations.size) { "Некорректная разметка локаций" }
                require(i == 0 || keys[i] > keys[i - 1]) { "Разметка локаций не упорядочена" }
            }
            return SceneIndex(keys, values, locations)
        }

        val EMPTY = SceneIndex(LongArray(0), ShortArray(0), emptyList())
    }
}
