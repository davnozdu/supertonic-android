package com.brahmadeo.supertonic.tts.utils

/** Dates and years spelled out in their final case before any model sees them:
 * "20 августа 1991 года" -> "двадцатого августа тысяча девятьсот девяносто первого года",
 * "в 1905 году" -> "в тысяча девятьсот пятом году", "к 1 сентября" -> "к первому сентября",
 * "с 1941 по 1945 год", "1941–1945 гг.", "20.08.91".
 * Shared by the LLM input ([RussianNumberNormalizer.prepareForLlm]) and the offline path
 * ([RussianBookNormalizer]), so both read a date the same way. The case comes from the
 * preposition before it; the LLM may still inflect the returned numeral spans by context. */
object RussianDates {
    private val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val monthAlternatives = months.joinToString("|")
    private const val NO_NUMBER_BEFORE = "(?<![\\p{L}\\d.,:/-])"
    // One pass over all formats, so a year after a day + month is never read twice.
    private val pattern = Regex(
        // 1-3: 20.08.1991 or 20.08.91
        "(?<![\\p{L}\\d.,])(\\d{1,2})[./](\\d{1,2})[./](\\d{4}|\\d{2})(?![\\p{L}\\d]|[.,/]\\d)" +
            // 4-7: 20 августа [1991 [года|г.]]
            "|$NO_NUMBER_BEFORE(\\d{1,2})\\s+($monthAlternatives)(?:\\s+(\\d{3,4})(?:\\s*(года|г\\.)(?![\\p{L}]))?)?(?![\\p{L}\\d])" +
            // 8-11: 1941–1945 [гг.|годы|годах|годов|...]
            "|$NO_NUMBER_BEFORE(\\d{4})\\s*([–—-])\\s*(\\d{4})(?:\\s*(гг\\.|годами|годах|годов|годам|годы|года|г\\.)(?![\\p{L}]))?(?![\\p{L}\\d])" +
            // 12-13: 1991 году / года / год / годом / г.
            "|$NO_NUMBER_BEFORE(\\d{3,4})\\s*(годом|году|года|год|г\\.)(?![\\p{L}])" +
            // 14: a bare year after a preposition: "с 1941 по", "к 2000,", "в 1991."
            "|$NO_NUMBER_BEFORE(\\d{4})(?=\\s*(?:[,.;:!?)»]|$)|\\s+[—–]|\\s+(?:по|до)\\s+\\d{4}|\\s+([а-яё]+))",
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

    /** A bare year after a preposition: "с/от/до/после 1941" -> -ого, "по 1945" -> -ый, "к 2000" -> -ому, "в 1991" -> -ом. */
    private fun bareYearEnding(before: String): String? = when (before) {
        "с", "со", "от", "до", "после", "около", "начиная", "конца", "начала", "середины" -> "ого"
        "по" -> "ый"
        "к", "ко" -> "ому"
        "в", "во" -> "ом"
        else -> null
    }

    private val countEndings = listOf("ах", "ях", "ам", "ям", "ами", "ями", "ов", "ев", "ей")
    private val countWords = setOf("человек", "лет", "раз", "штук", "тонн", "минут", "секунд", "мест", "книг", "страниц",
        "слов", "единиц", "копеек", "солдат", "граммов", "душ", "верст", "миль", "сажен", "пудов", "десятин")

    /** A noun counted by the number before it ("1500 метрах", "1000-1500 человек"): then the number is not a year. */
    fun looksCounted(word: String): Boolean {
        val w = word.lowercase()
        return w.isNotEmpty() && !w.startsWith("год") && (w in countWords || countEndings.any { w.endsWith(it) })
    }

    private fun year(digits: String): Int {
        val y = digits.toInt()
        if (digits.length != 2) return y
        val century = java.time.Year.now().value / 100 * 100
        return if (y <= java.time.Year.now().value % 100) century + y else century - 100 + y
    }

    private fun ord(n: Int, ending: String) = RussianBookNormalizer.ordinal(n, ending)

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
                    val day = g[1].toInt(); val month = g[2].toInt(); val year = year(g[3])
                    if (runCatching { java.time.LocalDate.of(year, month, day) }.isFailure) null
                    else listOf(ord(day, dayEnding(before)) to true, " ${months[month - 1]} " to false,
                        ord(year, "ого") to true, " года" to false)
                }
                g[4].isNotEmpty() -> {
                    val day = g[4].toInt()
                    if (day !in 1..31) null else buildList {
                        add(ord(day, dayEnding(before)) to true)
                        add(" ${g[5]}" to false)
                        if (g[6].isNotEmpty()) {
                            add(" " to false); add(ord(g[6].toInt(), "ого") to true)
                            if (g[7].isNotEmpty()) add(" года" to false)
                        }
                    }
                }
                g[8].isNotEmpty() -> {
                    val first = g[8].toInt(); val last = g[10].toInt()
                    val nextWord = word.find(text.substring(m.range.last + 1).take(30))?.value.orEmpty()
                    if (first !in 1000..2100 || last !in 1000..2100 || last <= first ||
                        g[11].isEmpty() && looksCounted(nextWord)) null else {
                        val (ending, noun) = when (g[11].lowercase()) {
                            "годы" -> "ый" to " годы"
                            "годах" -> "ом" to " годах"
                            "годов", "года" -> "ого" to " ${g[11]}"
                            "годам" -> "ому" to " годам"
                            "годами" -> "ым" to " годами"
                            "" -> "ого" to ""
                            else -> when (before) { // "гг." / "г."
                                "в", "во" -> "ом" to " годах"
                                "к", "ко" -> "ому" to " годам"
                                else -> "ого" to " годов"
                            }
                        }
                        listOf(ord(first, ending) to true, " — " to false, ord(last, ending) to true, noun to false)
                    }
                }
                g[12].isNotEmpty() -> {
                    val year = g[12].toInt()
                    val noun = g[13].lowercase()
                    val (ending, written) = when (noun) {
                        "году" -> (if (before in setOf("к", "ко", "по")) "ому" else "ом") to g[13]
                        "года" -> "ого" to g[13]
                        "годом" -> "ым" to g[13]
                        "год" -> "ый" to g[13]
                        else -> when (before) { // "г.": the preposition decides the case.
                            "в", "во" -> "ом" to "году"
                            "к", "ко" -> "ому" to "году"
                            else -> "ого" to "года"
                        }
                    }
                    if (year < 100) null else listOf(ord(year, ending) to true, " $written" to false)
                }
                else -> {
                    val year = g[14].toInt()
                    // "в 1991 он уехал", "к 2000 всё изменится": a year; "в 1500 метрах", "с 1000 человек": a count.
                    val next = g[15].lowercase()
                    val counted = looksCounted(next)
                    val ending = if (counted) null else bareYearEnding(before)
                    if (year !in 1000..2100 || ending == null) null else listOf(ord(year, ending) to true)
                }
            } ?: continue
            out.append(text, start, m.range.first)
            val sentenceStart = text.substring(0, m.range.first).trimEnd(' ', '\t', '«', '"', '(', '—', '–', '-').let { it.isEmpty() || it.last() in ".!?…\n" }
            for ((index, piece) in pieces.withIndex()) {
                val words = if (index == 0 && sentenceStart) piece.first.replaceFirstChar(Char::uppercaseChar) else piece.first
                if (piece.second) number(words) else out.append(words)
            }
            // "г." / "гг." also ended the sentence: keep that full stop for the sentence splitter.
            if (m.value.endsWith(".") && text.substring(m.range.last + 1).trimStart(' ', '\t').let { it.isEmpty() || it[0] == '\n' || it[0].isUpperCase() }) out.append('.')
            start = m.range.last + 1
        }
        out.append(text, start, text.length)
        return Expanded(out.toString(), ranges)
    }
}
