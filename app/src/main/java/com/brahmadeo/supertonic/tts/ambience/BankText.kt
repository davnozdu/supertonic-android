package com.brahmadeo.supertonic.tts.ambience

/** Слова и оценка близости для базы звуков — без Android, чтобы решение можно было проверить на компьютере. */
object BankText {
    /** Минимальная близость описаний, чтобы взять клип из базы: почти дословное совпадение. */
    const val MIN_SCORE = 0.7f
    /** И второй кандидат должен быть заметно хуже, иначе не угадываем. */
    const val MIN_MARGIN = 0.15f

    private val STOP = setOf("the", "and", "with", "for", "from", "into", "onto", "some", "very", "then", "that", "this")

    fun tokens(text: String): Set<String> = text.lowercase()
        .split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in STOP }.toSet()

    /** Совпадение по объединению слов: одно лишнее слово уже снижает оценку, два — почти всегда отказ. */
    fun score(words: Set<String>, other: Set<String>): Float {
        if (words.isEmpty() || other.isEmpty()) return 0f
        val common = words.count { it in other }
        if (common == 0) return 0f
        return common.toFloat() / (words.size + other.size - common)
    }

    /** Ключ клипа базы, который подходит описанию: [bank] — вид -> (имя вида и его описание). */
    fun pick(words: Set<String>, bank: Map<String, String>): Pair<String, Float>? {
        if (words.isEmpty()) return null
        val scored = bank.asSequence().mapNotNull { (key, prompt) ->
            val other = tokens("$key $prompt")
            val score = score(words, other)
            if (score == 0f) null else key to score
        }.sortedByDescending { it.second }.toList()
        val best = scored.firstOrNull() ?: return null
        if (best.second < MIN_SCORE) return null
        if ((scored.getOrNull(1)?.second ?: 0f) >= best.second - MIN_MARGIN) return null
        return best
    }
}
