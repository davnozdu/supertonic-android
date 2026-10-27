package com.brahmadeo.supertonic.tts.llm

/** A sentence of a reader that hands over one sentence at a time (KOReader) found in a paragraph prepared ahead by the
 * LLM: the same words, now with stress marks, ё, punctuation and numbers spelled out. The LLM keeps the words, so the
 * paragraph's words and the prepared words are matched (longest common subsequence of bare words) and the sentence is cut
 * out of the prepared text between its first and last matched word, with the punctuation that belongs to it. Pure. */
object SentenceAlign {
    private val word = Regex("[\\p{L}\\p{N}\\u0301]+")

    /** A word of a text: its bare form and where it is. */
    data class Token(val bare: String, val start: Int, val end: Int)

    fun tokens(text: String): List<Token> = word.findAll(text).map {
        Token(it.value.replace("́", "").lowercase().replace('ё', 'е'), it.range.first, it.range.last + 1)
    }.toList()

    /** The original paragraph aligned to its prepared form once; [cut] finds sentences in it. */
    class Aligned(val original: String, val prepared: String) {
        val source = tokens(original)
        private val target = tokens(prepared)
        /** For each original word, the prepared word it matched, or -1. */
        private val map = IntArray(source.size) { -1 }
        val matchedShare: Double

        init {
            // LCS of bare words; a paragraph is a few hundred words at most.
            val n = source.size; val m = target.size
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
                dp[i][j] = if (source[i].bare == target[j].bare) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            var i = 0; var j = 0
            while (i < n && j < m) {
                when {
                    source[i].bare == target[j].bare -> { map[i] = j; i++; j++ }
                    dp[i + 1][j] >= dp[i][j + 1] -> i++
                    else -> j++
                }
            }
            matchedShare = if (n == 0) 0.0 else map.count { it >= 0 }.toDouble() / n
        }

        /** The prepared form of [sentence], or null when it is not in this paragraph or too little of it matched. */
        fun cut(sentence: String): Cut? {
            val words = tokens(sentence).map { it.bare }
            if (words.isEmpty() || words.size > source.size) return null
            val places = (0..source.size - words.size).filter { s -> words.indices.all { source[s + it].bare == words[it] } }
            // The same words twice in a paragraph («Мое сердечное пожелание» and later «…мое сердечное пожелание!»): the
            // place spelled exactly like the sentence (capitals, punctuation), else the first.
            val at = places.firstOrNull { s -> places.size > 1 && sentence.contains(original.substring(source[s].start, source[s + words.size - 1].end)) }
                ?: places.firstOrNull() ?: return null
            val last = at + words.size - 1
            val inside = (at..last).filter { map[it] >= 0 }
            // Numbers spelled out (one word → several) leave some words unmatched; most must match.
            if (inside.size * 10 < words.size * 7) return null
            // The prepared words of the sentence: from the one after the previous sentence's last match to the one
            // before the next sentence's first match, so spelled-out numbers at its edges stay with it.
            val prevMatch = (at - 1 downTo 0).firstOrNull { map[it] >= 0 }?.let { map[it] } ?: -1
            val nextMatch = (last + 1 until source.size).firstOrNull { map[it] >= 0 }?.let { map[it] } ?: target.size
            val first = maxOf(prevMatch + 1, 0).coerceAtMost(map[inside.first()])
            val final = (nextMatch - 1).coerceAtLeast(map[inside.last()])
            // Leading punctuation of its own («— », «») after the previous sentence's closing marks; trailing up to
            // the space that ends it.
            val before = if (first == 0) prepared.substring(0, target[first].start)
                else prepared.substring(target[first - 1].end, target[first].start)
            val lead = if (first == 0) before else before.indexOfFirst { it.isWhitespace() }.let { if (it < 0) "" else before.substring(it) }
            val after = if (final + 1 >= target.size) prepared.substring(target[final].end)
                else prepared.substring(target[final].end, target[final + 1].start)
            val trail = after.indexOfFirst { it.isWhitespace() }.let { if (it < 0) after else after.substring(0, it) }
            val start = target[first].start - lead.trimStart().length
            val end = target[final].end + trail.length
            return Cut(start, end, prepared.substring(start, end).trim())
        }
    }

    data class Cut(val start: Int, val end: Int, val text: String)

    /** The voice parts of a cut: the paragraph's plan (its texts concatenate to the prepared paragraph) clipped to it.
     * Empty when the plan does not cover the paragraph (then the sentence is read without roles). */
    fun clipPlan(prepared: String, plan: List<VoiceRoleText>, cut: Cut): List<VoiceRoleText> {
        if (plan.isEmpty() || plan.joinToString("") { it.text } != prepared) return emptyList()
        val out = mutableListOf<VoiceRoleText>()
        var at = 0
        for (part in plan) {
            val s = maxOf(at, cut.start); val e = minOf(at + part.text.length, cut.end)
            if (s < e) prepared.substring(s, e).takeIf { it.isNotBlank() }?.let { out += part.copy(text = it) }
            at += part.text.length
        }
        // Trimmed like the cut's text, so the parts and the text are the same characters.
        if (out.isNotEmpty()) {
            out[0] = out[0].copy(text = out[0].text.trimStart())
            out[out.lastIndex] = out[out.lastIndex].copy(text = out[out.lastIndex].text.trimEnd())
        }
        return out.filter { it.text.isNotEmpty() }
    }
}
