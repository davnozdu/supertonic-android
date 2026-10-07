package com.brahmadeo.supertonic.tts.utils

/** Dates and years spelled out in their final case before any model sees them:
 * "20 августа 1991 года" -> "двадцатого августа тысяча девятьсот девяносто первого года",
 * "в 1905 году" -> "в тысяча девятьсот пятом году", "к 1 сентября" -> "к первому сентября".
 * Shared by the LLM input ([RussianNumberNormalizer.prepareForLlm]) and the offline path
 * ([RussianBookNormalizer]), so both read a date the same way. The case comes from the
 * preposition before it; the LLM may still inflect the returned numeral spans by context. */
object RussianDates {
    private val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val monthAlternatives = months.joinToString("|")
    // One pass over three formats, so a year after a day + month is never read twice.
    private val pattern = Regex(
        "(?<![\\p{L}\\d.,])(\\d{1,2})[./](\\d{1,2})[./](\\d{4})(?![\\p{L}\\d]|[.,/]\\d)" +
            "|(?<![\\p{L}\\d.,:/-])(\\d{1,2})\\s+($monthAlternatives)(?:\\s+(\\d{3,4})(?:\\s*(года|г\\.)(?![\\p{L}]))?)?(?![\\p{L}\\d])" +
            "|(?<![\\p{L}\\d.,:/-])(\\d{3,4})\\s*(годом|году|года|год|г\\.)(?![\\p{L}])",
        RegexOption.IGNORE_CASE)
    private val word = Regex("[\\p{L}]+")

    data class Expanded(val text: String, val ranges: List<IntRange>)

    private fun previousWord(text: String, end: Int): String =
        word.findAll(text.substring(maxOf(0, end - 40), end)).lastOrNull()?.value?.lowercase().orEmpty()

    /** Ordinal ending of a day of month: "двадцатого августа", "к двадцатому", "на двадцатое". */
    private fun dayEnding(before: String) = when (before) {
        "к", "ко" -> "ому"
        "на", "по", "за", "про", "сегодня", "завтра", "вчера", "послезавтра", "позавчера" -> "ое"
        "перед", "между", "над", "под" -> "ым"
        "о", "об", "при" -> "ом"
        else -> "ого"
    }

    fun expand(text: String): Expanded {
        val out = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        var start = 0
        fun number(words: String) { val from = out.length; out.append(words); ranges += from until out.length }
        for (m in pattern.findAll(text)) {
            val g = m.groupValues
            val before = previousWord(text, m.range.first)
            val pieces: List<Pair<String, Boolean>> = when {
                g[1].isNotEmpty() -> {
                    val day = g[1].toInt(); val month = g[2].toInt(); val year = g[3].toInt()
                    if (runCatching { java.time.LocalDate.of(year, month, day) }.isFailure) null
                    else listOf(RussianBookNormalizer.ordinal(day, dayEnding(before)) to true, " ${months[month - 1]} " to false,
                        RussianBookNormalizer.ordinal(year, "ого") to true, " года" to false)
                }
                g[4].isNotEmpty() -> {
                    val day = g[4].toInt()
                    if (day !in 1..31) null else buildList {
                        add(RussianBookNormalizer.ordinal(day, dayEnding(before)) to true)
                        add(" ${g[5]}" to false)
                        if (g[6].isNotEmpty()) {
                            add(" " to false); add(RussianBookNormalizer.ordinal(g[6].toInt(), "ого") to true)
                            if (g[7].isNotEmpty()) add(" года" to false)
                        }
                    }
                }
                else -> {
                    val year = g[8].toInt()
                    val noun = g[9].lowercase()
                    val (ending, written) = when (noun) {
                        "году" -> (if (before in setOf("к", "ко", "по")) "ому" else "ом") to g[9]
                        "года" -> "ого" to g[9]
                        "годом" -> "ым" to g[9]
                        "год" -> "ый" to g[9]
                        else -> when (before) { // "г.": the preposition decides the case.
                            "в", "во" -> "ом" to "году"
                            "к", "ко" -> "ому" to "году"
                            else -> "ого" to "года"
                        }
                    }
                    if (year < 100) null else listOf(RussianBookNormalizer.ordinal(year, ending) to true, " $written" to false)
                }
            } ?: continue
            out.append(text, start, m.range.first)
            val sentenceStart = text.substring(0, m.range.first).trimEnd(' ', '\t', '«', '"', '(', '—', '–', '-').let { it.isEmpty() || it.last() in ".!?…\n" }
            for ((index, piece) in pieces.withIndex()) {
                val words = if (index == 0 && sentenceStart) piece.first.replaceFirstChar(Char::uppercaseChar) else piece.first
                if (piece.second) number(words) else out.append(words)
            }
            // "г." also ended the sentence: keep that full stop for the sentence splitter.
            if (m.value.endsWith(".") && text.substring(m.range.last + 1).trimStart(' ', '\t').let { it.isEmpty() || it[0] == '\n' || it[0].isUpperCase() }) out.append('.')
            start = m.range.last + 1
        }
        out.append(text, start, text.length)
        return Expanded(out.toString(), ranges)
    }
}
