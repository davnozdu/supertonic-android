package com.brahmadeo.supertonic.tts.books

/** Where each part of a prepared book takes place («Фоновые звуки локаций»), so the reader's text gets its location
 * without the LLM. Like [ReplyIndex], by word: a word is found by its letters and the [WINDOW] − 1 words before it
 * (across paragraphs), so any cut of the text works (Moon+ by paragraph, KOReader by sentence). Only every second
 * word is stored (half the size; a sentence of four words still has a hit). Text outside any scene is stored too, as
 * [NONE] (there the background music plays); a piece with no hit at all keeps the current sound.
 * Stored as sorted 8-byte keys + 2-byte location numbers (−1 = no scene), base64. */
class SceneIndex private constructor(private val keys: LongArray, private val values: ShortArray, val locations: List<Location>,
                                     /** «Звуки событий» of the book (empty when none were marked). */
                                     val events: EventIndex = EventIndex.EMPTY,
                                     /** «Музыка по настроению» of the book (empty when none were marked). */
                                     val moods: MoodIndex = MoodIndex.EMPTY) {
    /** [sound]: the English prompt of the steady background for the ambience generator; [accents]: occasional events
     * of the place (a distant locomotive whistle), each in a variant of its own; [paragraphs]: how much of the book is there. */
    data class Location(val id: String, val name: String, val sound: String, val paragraphs: Int, val accents: List<String> = emptyList(),
                        /** outdoor / room / large_room / hall / vehicle / cave — the voice's room («Акустика помещения»). */
                        val acoustics: String = "")

    val size get() = keys.size

    /** What the index says about a piece of text. */
    sealed interface Found
    data class At(val location: Location) : Found
    data object Outside : Found
    data object Unknown : Found

    /** The location of [text] after the words [before] (most words decide; [Outside] — no scene there; [Unknown] —
     * no word of it in the index). */
    fun find(text: String, before: List<String>): Found {
        if (keys.isEmpty()) return Unknown
        val window = ArrayDeque(before.takeLast(WINDOW - 1))
        val votes = HashMap<Int, Int>()
        for (w in ReplyIndex.words(text)) {
            window.addLast(w); while (window.size > WINDOW) window.removeFirst()
            if (window.size < WINDOW) continue
            val i = keys.binarySearch(ReplyIndex.key(window))
            if (i >= 0) votes.merge(values[i].toInt(), 1, Int::plus)
        }
        val best = votes.maxByOrNull { it.value }?.key ?: return Unknown
        return locations.getOrNull(best)?.let(::At) ?: Outside
    }

    fun resolve(text: String, before: List<String>): Location? = (find(text, before) as? At)?.location

    fun toJson(): org.json.JSONObject {
        val bytes = java.nio.ByteBuffer.allocate(keys.size * 10)
        for (i in keys.indices) { bytes.putLong(keys[i]); bytes.putShort(values[i]) }
        return org.json.JSONObject().put("algorithm", ALGORITHM)
            .put("locations", org.json.JSONArray(locations.map {
                org.json.JSONObject().put("id", it.id).put("name", it.name).put("sound", it.sound).put("paragraphs", it.paragraphs)
                    .put("accents", org.json.JSONArray(it.accents)).put("acoustics", it.acoustics)
            }))
            .put("index", java.util.Base64.getEncoder().encodeToString(bytes.array()))
            .apply { if (events.events.isNotEmpty()) put("events", events.toJson()) }
            .apply { if (moods.moods.isNotEmpty()) put("moods", moods.toJson()) }
    }

    companion object {
        const val ALGORITHM = "words4-even-fnv1a64-v2"
        /** Location number of text outside any scene. */
        const val NONE = -1
        const val WINDOW = ReplyIndex.WINDOW
        private val ID = Regex("[a-z0-9_]{1,40}")

        /** Feed every paragraph of the book in reading order with its location number (null: no scene → [NONE]). */
        class Builder(private val locations: List<Location>) {
            private val window = ArrayDeque<String>()
            private val entries = HashMap<Long, Int>()
            private val clash = HashSet<Long>()
            private var word = 0
            fun add(paragraph: String, location: Int?) {
                for (w in ReplyIndex.words(paragraph)) {
                    window.addLast(w); while (window.size > WINDOW) window.removeFirst()
                    if (word++ % 2 != 0 || window.size < WINDOW) continue
                    val value = location ?: NONE
                    val k = ReplyIndex.key(window)
                    if (k in clash) continue
                    val old = entries.putIfAbsent(k, value)
                    if (old != null && old != value) { entries.remove(k); clash += k }
                }
            }
            fun build(events: EventIndex = EventIndex.EMPTY, moods: MoodIndex = MoodIndex.EMPTY): SceneIndex {
                val sorted = entries.entries.sortedBy { it.key }
                return SceneIndex(LongArray(sorted.size) { sorted[it].key }, ShortArray(sorted.size) { sorted[it].value.toShort() }, locations, events, moods)
            }
        }

        fun parse(o: org.json.JSONObject): SceneIndex {
            // v1 (before 5.0.6-beta41) had no NONE marks: still read, outside text is then simply not in it.
            require(o.getString("algorithm") in setOf(ALGORITHM, "words4-even-fnv1a64")) { "Неизвестный способ разметки локаций: ${o.optString("algorithm")}" }
            val list = o.getJSONArray("locations")
            val locations = (0 until list.length()).map { list.getJSONObject(it) }.map {
                val id = it.getString("id")
                require(ID.matches(id)) { "Некорректный идентификатор локации: $id" }
                val accents = it.optJSONArray("accents")?.let { a -> (0 until a.length()).map { k -> a.getString(k) } }.orEmpty().take(4)
                Location(id, it.getString("name"), it.getString("sound"), it.optInt("paragraphs"), accents, it.optString("acoustics"))
            }
            require(locations.size <= Short.MAX_VALUE && locations.map { it.id }.distinct().size == locations.size) { "Локации повторяются" }
            val bytes = java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(o.getString("index")))
            require(bytes.remaining() % 10 == 0) { "Некорректная разметка локаций" }
            val n = bytes.remaining() / 10
            val keys = LongArray(n); val values = ShortArray(n)
            for (i in 0 until n) {
                keys[i] = bytes.long; values[i] = bytes.short
                require(values[i] in NONE until locations.size) { "Некорректная разметка локаций" }
                require(i == 0 || keys[i] > keys[i - 1]) { "Разметка локаций не упорядочена" }
            }
            val events = o.optJSONObject("events")?.let { EventIndex.parse(it) } ?: EventIndex.EMPTY
            val moods = o.optJSONObject("moods")?.let { MoodIndex.parse(it) } ?: MoodIndex.EMPTY
            return SceneIndex(keys, values, locations, events, moods)
        }

        val EMPTY = SceneIndex(LongArray(0), ShortArray(0), emptyList())
    }
}
