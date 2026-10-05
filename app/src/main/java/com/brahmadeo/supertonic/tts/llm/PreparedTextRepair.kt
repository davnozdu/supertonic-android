package com.brahmadeo.supertonic.tts.llm

/** Salvage an almost-correct LLM answer instead of dropping the whole paragraph to the offline
 * dictionary. Words aligned with the source keep the model's stress (written ё restored, marks
 * not on a vowel removed); rewritten, added or dropped words are replaced by the source text.
 * The result must still pass [PreparedTextValidator]; larger divergences stay rejected. */
object PreparedTextRepair {
    private val words = Regex("\\+?[\\p{L}\\p{N}]+(?:[+́][\\p{L}\\p{N}]*)*")
    private const val vowels = "аеёиоуыэюяАЕЁИОУЫЭЮЯ"
    private fun plain(s: String) = s.replace("+", "").replace("́", "")
    private fun key(s: String) = plain(s).lowercase().replace('ё', 'е')

    data class Repair(val text: String, val replacedWords: Int)

    fun repair(original: String, proposed: String): Repair? {
        val a = words.findAll(original).toList()
        val b = words.findAll(proposed).toList()
        if (a.isEmpty() || b.isEmpty() || a.size.toLong() * b.size > 4_000_000L) return null
        val ka = a.map { key(it.value) }; val kb = b.map { key(it.value) }
        // Longest common subsequence of word keys.
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed())
            dp[i][j] = if (ka[i] == kb[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val matches = ArrayList<Pair<Int, Int>>()
        var i = 0; var j = 0
        while (i < a.size && j < b.size) when {
            ka[i] == kb[j] -> { matches += i to j; i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> i++
            else -> j++
        }
        val replaced = a.size - matches.size
        if (replaced > maxOf(2, a.size / 20)) return null
        val out = StringBuilder()
        var prevA = -1; var prevB = -1
        fun sourceSpan(fromA: Int, toA: Int) = original.substring(if (fromA < 0) 0 else a[fromA].range.last + 1, if (toA >= a.size) original.length else a[toA].range.first)
        fun proposedSpan(fromB: Int, toB: Int) = proposed.substring(if (fromB < 0) 0 else b[fromB].range.last + 1, if (toB >= b.size) proposed.length else b[toB].range.first)
        for ((ia, ib) in matches + (a.size to b.size)) {
            // Words between two matches: the model rewrote, added or dropped something there.
            out.append(if (ia - prevA == 1 && ib - prevB == 1) proposedSpan(prevB, ib) else sourceSpan(prevA, ia))
            if (ia < a.size) out.append(fixWord(a[ia].value, b[ib].value))
            prevA = ia; prevB = ib
        }
        return Repair(out.toString(), replaced)
    }

    /** Same word: restore written ё from the source and keep one acute mark, only after a vowel. */
    private fun fixWord(source: String, target: String): String {
        if (source.contains('+') || source.contains('́')) return source
        val sourcePlain = plain(source)
        val result = StringBuilder()
        var letter = 0
        var accented = false
        for (ch in target.replace("+", "")) {
            if (ch == '́') {
                if (!accented && result.isNotEmpty() && result.last() in vowels) { result.append(ch); accented = true }
                continue
            }
            val src = sourcePlain.getOrNull(letter)
            result.append(if (src != null && src in "ёЁ" && ch in "еЕ") src else ch)
            letter++
        }
        return result.toString()
    }
}
