package com.brahmadeo.supertonic.tts.llm

/** Reject rewriting, lost words, changed numbers, and invalid stress markers. */
object PreparedTextValidator {
    private val words = Regex("\\+?[\\p{L}\\p{N}]+(?:[+\u0301][\\p{L}\\p{N}]*)*")
    private val numbers = Regex("[0-9]+(?:[.,:/-][0-9]+)*")
    private val vowels = "аеёиоуыэюяАЕЁИОУЫЭЮЯ"
    private fun plain(s: String) = s.replace("+", "").replace("\u0301", "")
    private fun symbols(s: String) = s.filter { !it.isLetterOrDigit() && !it.isWhitespace() && it !in "+\u0301,.;:!?…—–-\"'«»“”„()[]" }
    fun validate(original: String, proposed: String, allowPunctuation: Boolean = true, allowStress: Boolean = true): String? {
        if (proposed.length > original.length * 2 + 100 || proposed.isBlank()) return null
        val a = words.findAll(original).toList()
        val b = words.findAll(proposed).toList()
        if (a.size != b.size || a.isEmpty()) return null
        if (numbers.findAll(original).map { it.value }.toList() != numbers.findAll(proposed).map { it.value }.toList()) return null
        if (symbols(original) != symbols(proposed)) return null
        if (original.count { it == '\n' } != proposed.count { it == '\n' }) return null
        if (original.filter { it in "\"'«»“”„()[]" } != proposed.filter { it in "\"'«»“”„()[]" }) return null
        // A hyphen inside a word is spelling, not freely editable punctuation.
        for (i in 0 until a.lastIndex) {
            val sourceGap = original.substring(a[i].range.last + 1, a[i + 1].range.first)
            val targetGap = proposed.substring(b[i].range.last + 1, b[i + 1].range.first)
            if ((sourceGap == "-") != (targetGap == "-")) return null
            if (sourceGap.count { it == '\n' } != targetGap.count { it == '\n' }) return null
            if (sourceGap.filter { it in "\"'«»“”„()[]" } != targetGap.filter { it in "\"'«»“”„()[]" }) return null
        }
        fun anchors(s: String) = s.filter { it == '\n' || it in "\"'«»“”„()[]" }
        if (anchors(original.substring(0, a.first().range.first)) != anchors(proposed.substring(0, b.first().range.first))) return null
        if (anchors(original.substring(a.last().range.last + 1)) != anchors(proposed.substring(b.last().range.last + 1))) return null
        val replacements = mutableListOf<Pair<IntRange, String>>()
        for (i in a.indices) {
            val source = a[i].value
            val target = b[i].value.replace("+", "") // '+' is reserved for explicit input; LLM must return acute marks.
            if (plain(source).lowercase() != plain(target).lowercase()) return null
            if (b[i].value.contains('+')) return null
            if (target.count { it == '\u0301' } > 1) return null
            val mark = target.indexOf('\u0301')
            if (mark >= 0 && (mark == 0 || target[mark - 1] !in vowels)) return null
            val sourcePlain = plain(source)
            val replacement = if (!allowStress || source.contains('+') || source.contains('\u0301')) source else {
                if (mark < 0) sourcePlain else sourcePlain.substring(0, mark) + '\u0301' + sourcePlain.substring(mark)
            }
            replacements += b[i].range to replacement
        }
        if (!allowPunctuation) {
            val out = StringBuilder(original)
            for (i in a.indices.reversed()) out.replace(a[i].range.first, a[i].range.last + 1, replacements[i].second)
            return out.toString()
        }
        val out = StringBuilder(proposed)
        for ((range, replacement) in replacements.asReversed()) out.replace(range.first, range.last + 1, replacement)
        return out.toString()
    }
}
