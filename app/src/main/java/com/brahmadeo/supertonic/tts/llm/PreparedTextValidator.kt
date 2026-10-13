package com.brahmadeo.supertonic.tts.llm

/** Reject rewriting, lost words, changed numbers, and invalid stress markers. */
object PreparedTextValidator {
    private val words = Regex("\\+?[\\p{L}\\p{N}]+(?:[+\u0301][\\p{L}\\p{N}]*)*")
    private val numbers = Regex("[0-9]+(?:[.,:/-][0-9]+)*")
    private val vowels = "аеёиоуыэюяАЕЁИОУЫЭЮЯ"
    private fun plain(s: String) = s.replace("+", "").replace("\u0301", "")
    /** Quotes and apostrophes: an LLM may write «» for "" or ' for ’; their places are checked, the source's characters
     * are put back. Brackets must stay as they are. */
    private const val QUOTES = "\"'«»“”„‚‘’‹›"
    private const val DELIMITERS = QUOTES + "()[]"
    private const val SENTENCE_END = ".!?…"
    private const val INNER_MARKS = ",;:—–"
    private fun symbols(s: String) = s.filter { !it.isLetterOrDigit() && !it.isWhitespace() && it !in "+\u0301,.;:!?…—–-" && it !in DELIMITERS }
    private fun shape(s: String) = s.filter { it in DELIMITERS }.map { if (it in QUOTES) 'Q' else it }.joinToString("")
    fun validate(original: String, response: String, allowPunctuation: Boolean = true, allowStress: Boolean = true,
                 numberRanges: List<IntRange> = emptyList(), allowYo: Boolean = true, requireStress: Boolean = allowStress,
                 onReject: (String) -> Unit = {}): String? {
        fun reject(reason: String): String? { onReject(reason); return null }
        // Some providers insert dialogue quotes despite the instruction. If the
        // source has none, discard those formatting additions before validation.
        // Existing source quotes/brackets must still retain their exact anchors.
        val stripped = if (original.none { it in DELIMITERS }) response.filterNot { it in DELIMITERS } else response
        if (shape(original) != shape(stripped)) return reject("quotes")
        val sourceQuotes = original.filter { it in QUOTES }.iterator()
        val proposed = stripped.map { if (it in QUOTES) sourceQuotes.next() else it }.joinToString("")
        if (proposed.length > original.length * 2 + 100 || proposed.isBlank()) return reject("response_length")
        val a = words.findAll(original).toList()
        val b = words.findAll(proposed).toList()
        if (a.size != b.size || a.isEmpty()) return reject("word_count")
        // Detached combining accents are not captured as words. Never let them
        // reach the tokenizer; retain mathematical '+' operators in word gaps.
        var nextWord = 0
        for (index in proposed.indices) {
            while (nextWord < b.size && b[nextWord].range.last < index) nextWord++
            if (proposed[index] == '\u0301' && (nextWord == b.size || index !in b[nextWord].range)) return reject("detached_accent")
        }
        if (numbers.findAll(original).map { it.value }.toList() != numbers.findAll(proposed).map { it.value }.toList()) return reject("number_changed")
        if (symbols(original) != symbols(proposed)) return reject("unsupported_symbols")
        if (original.count { it == '\n' } != proposed.count { it == '\n' }) return reject("paragraph_count")
        if (original.filter { it in DELIMITERS } != proposed.filter { it in DELIMITERS }) return reject("quotes")
        // A hyphen inside a word is spelling, not freely editable punctuation.
        for (i in 0 until a.lastIndex) {
            val sourceGap = original.substring(a[i].range.last + 1, a[i + 1].range.first)
            val targetGap = proposed.substring(b[i].range.last + 1, b[i + 1].range.first)
            if ((sourceGap == "-") != (targetGap == "-")) return reject("word_hyphen")
            if (sourceGap.count { it == '+' } != targetGap.count { it == '+' }) return reject("plus_operator")
            if (sourceGap.count { it == '\n' } != targetGap.count { it == '\n' }) return reject("paragraph_moved")
            if (sourceGap.filter { it in DELIMITERS } != targetGap.filter { it in DELIMITERS }) return reject("quotes_moved")
        }
        fun anchors(s: String) = s.filter { it == '\n' || it == '+' || it in DELIMITERS }
        if (anchors(original.substring(0, a.first().range.first)) != anchors(proposed.substring(0, b.first().range.first))) return reject("edge_anchors")
        if (anchors(original.substring(a.last().range.last + 1)) != anchors(proposed.substring(b.last().range.last + 1))) return reject("edge_anchors")
        val replacements = mutableListOf<Pair<IntRange, String>>()
        var needsStress = false
        var suppliedStress = false
        for (i in a.indices) {
            val source = a[i].value
            val target = b[i].value.replace("+", "") // '+' is reserved for explicit input; LLM must return acute marks.
            fun numericGender(word: String) = when (word) { "один", "одна", "одно" -> "один"; "два", "две" -> "два"; else -> word }
            val sourcePlain = plain(source)
            val targetPlain = plain(target)
            fun foldYo(s: String) = s.lowercase().replace('ё', 'е')
            for (j in sourcePlain.indices) if (sourcePlain[j] in "ёЁ" && targetPlain.getOrNull(j)?.lowercaseChar() != 'ё') return reject("lost_yo")
            val changedGender = foldYo(sourcePlain) != foldYo(targetPlain)
            val inNumber = numberRanges.any { a[i].range.first in it && a[i].range.last in it }
            // Inside an expanded number any case/ordinal form of the same component is allowed
            // ("сто одну книгу", "без пяти минут", "пятом году"); its value cannot change.
            // Only the last word agrees with the noun; earlier ones keep their gender ("одна тысяча").
            fun gender(w: String) = when (foldYo(w)) { "одна", "одной", "одну", "одною", "две" -> 'f'; "один", "одного", "одному", "одним", "одном", "одно", "два" -> 'm'; else -> null }
            val lastInNumber = numberRanges.any { a[i].range.last == it.last }
            val genderKept = lastInNumber || gender(sourcePlain) == null || gender(targetPlain) == null || gender(sourcePlain) == gender(targetPlain)
            val sameNumeral = genderKept && NumeralForms.value(sourcePlain)?.let { it == NumeralForms.value(targetPlain) } == true
            if (changedGender && !(inNumber && (sameNumeral || (numericGender(foldYo(sourcePlain)) == numericGender(foldYo(targetPlain)) &&
                        numberRanges.any { a[i].range.last == it.last })))) return reject("rewritten_word")
            if (b[i].value.contains('+') && !source.contains('+')) return reject("unexpected_plus")
            val explicit = source.contains('+') || source.contains('\u0301')
            if (!explicit && source.none { it in "ёЁ" } && source.count { it in vowels } > 1) {
                needsStress = true
                if (target.contains('\u0301') || (allowYo && target.any { it in "ёЁ" })) suppliedStress = true
            }
            if (!explicit && target.count { it == '\u0301' } > 1) return reject("multiple_accents")
            val mark = target.indexOf('\u0301')
            if (!explicit && mark >= 0 && (mark == 0 || target[mark - 1] !in vowels)) return reject("accent_not_on_vowel")
            val base = if (changedGender) targetPlain.lowercase().let { if (source.first().isUpperCase()) it.replaceFirstChar(Char::uppercaseChar) else it } else {
                sourcePlain.mapIndexed { j, ch ->
                    if (allowYo && ch in "еЕ" && targetPlain.getOrNull(j)?.lowercaseChar() == 'ё') {
                        if (ch.isUpperCase()) 'Ё' else 'ё'
                    } else ch
                }.joinToString("")
            }
            val replacement = if (source.contains('+') || source.contains('\u0301')) source else {
                if (!allowStress || mark < 0) base else base.substring(0, mark) + '\u0301' + base.substring(mark)
            }
            replacements += b[i].range to replacement
        }
        // A punctuation-only answer is not successful stress preparation.
        // Already marked text and single-syllable words need no new marks.
        if (requireStress && needsStress && !suppliedStress) return reject("missing_stress")
        if (!allowPunctuation) {
            val out = StringBuilder(original)
            for (i in a.indices.reversed()) out.replace(a[i].range.first, a[i].range.last + 1, replacements[i].second)
            return out.toString()
        }
        // Punctuation may be added or corrected, never deleted: a sentence end of the source stays («Да. Нет.» is not
        // «Да Нет»: intonation, pauses, the homograph's sentence), and so does the author's comma, colon or dash where
        // the answer left no mark at all. Such a gap is taken from the source as a whole.
        // The leading dialogue dash marks a reply for the voices: it is kept like any other mark.
        fun keep(source: String, target: String, dash: Boolean = false): String {
            val inner = if (dash) INNER_MARKS + "-" else INNER_MARKS
            val lostEnd = source.any { it in SENTENCE_END } && target.none { it in SENTENCE_END }
            val lostMark = source.any { it in inner } && target.none { it in inner || it in SENTENCE_END }
            return if (lostEnd || lostMark) source else target
        }
        val out = StringBuilder(keep(original.substring(0, a.first().range.first), proposed.substring(0, b.first().range.first), dash = true))
        for (i in a.indices) {
            out.append(replacements[i].second)
            if (i == a.lastIndex) break
            out.append(keep(original.substring(a[i].range.last + 1, a[i + 1].range.first), proposed.substring(b[i].range.last + 1, b[i + 1].range.first)))
        }
        out.append(keep(original.substring(a.last().range.last + 1), proposed.substring(b.last().range.last + 1)))
        return out.toString()
    }
}
