package com.brahmadeo.supertonic.tts.books

/** «Нагнетание напряжения»: уровень напряжения каждого места подготовленной книги, 0–3 (0 — спокойно, 1 — тревожно,
 * 2 — страшно / напряжённо, 3 — пик). Ставит LLM при разметке сцен по самому тексту, для любого жанра: тревога в
 * детективе, страх в ужасах, ссора в романе. Ищется окнами слов, как [MoodIndex] (каждое второе слово); хранится
 * отсортированными 8-байтными ключами и уровнем в 1 байт, base64. Плеер по уровню плавно меняет силу музыки. */
class TensionIndex private constructor(private val keys: LongArray, private val levels: ByteArray) {

    /** Уровень [text] после слов [before]: самый частый среди найденных окон; при равенстве — больший (нагнетание
     * важнее), null — ни одного окна не узнано (уровень остаётся прежним). */
    fun find(text: String, before: List<String>): Int? {
        if (keys.isEmpty()) return null
        val window = ArrayDeque(before.takeLast(WINDOW - 1))
        val votes = IntArray(LEVELS)
        var any = false
        for (w in ReplyIndex.words(text)) {
            window.addLast(w); while (window.size > WINDOW) window.removeFirst()
            if (window.size < WINDOW) continue
            val i = keys.binarySearch(ReplyIndex.key(window))
            if (i >= 0) { votes[levels[i].toInt()]++; any = true }
        }
        if (!any) return null
        var best = 0
        for (l in 1 until LEVELS) if (votes[l] >= votes[best]) best = l
        return best
    }

    val isEmpty get() = keys.isEmpty()

    fun toJson(): org.json.JSONObject {
        val bytes = java.nio.ByteBuffer.allocate(keys.size * 9)
        for (i in keys.indices) { bytes.putLong(keys[i]); bytes.put(levels[i]) }
        return org.json.JSONObject().put("index", java.util.Base64.getEncoder().encodeToString(bytes.array()))
    }

    companion object {
        const val WINDOW = ReplyIndex.WINDOW
        const val LEVELS = 4

        /** Каждый абзац в порядке чтения с его уровнем (null — неизвестен, окна не записываются). */
        class Builder {
            private val window = ArrayDeque<String>()
            private val entries = HashMap<Long, Int>()
            private var word = 0
            fun add(paragraph: String, level: Int?) {
                for (w in ReplyIndex.words(paragraph)) {
                    window.addLast(w); while (window.size > WINDOW) window.removeFirst()
                    if (word++ % 2 != 0 || window.size < WINDOW || level == null) continue
                    val k = ReplyIndex.key(window)
                    // Одно и то же окно в разных местах книги: берём больший уровень (лучше чуть раньше нагнести).
                    entries.merge(k, level.coerceIn(0, LEVELS - 1), ::maxOf)
                }
            }
            fun build(): TensionIndex {
                val sorted = entries.entries.sortedBy { it.key }
                return TensionIndex(LongArray(sorted.size) { sorted[it].key }, ByteArray(sorted.size) { sorted[it].value.toByte() })
            }
        }

        fun parse(o: org.json.JSONObject): TensionIndex {
            val bytes = java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(o.getString("index")))
            require(bytes.remaining() % 9 == 0) { "Некорректная разметка напряжения" }
            val n = bytes.remaining() / 9
            val keys = LongArray(n); val levels = ByteArray(n)
            for (i in 0 until n) {
                keys[i] = bytes.long; levels[i] = bytes.get()
                require(levels[i] in 0 until LEVELS) { "Некорректная разметка напряжения" }
                require(i == 0 || keys[i] > keys[i - 1]) { "Разметка напряжения не упорядочена" }
            }
            return TensionIndex(keys, levels)
        }

        val EMPTY = TensionIndex(LongArray(0), ByteArray(0))
    }
}
