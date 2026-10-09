package com.brahmadeo.supertonic.tts.utils

/**
 * Russian number-to-words converter for TTS pre-processing.
 *
 * Goal: stop the model from spelling digits out one-by-one
 * ("два ноль два пять") when it sees "2025" inside Russian text.
 *
 * Scope:
 * - Integers up to 10^12 with correct тысяч/миллион/миллиард agreement.
 * - Grouped integers: "1 101" -> "одна тысяча сто один".
 * - Decimal numbers with comma or point, up to nine fractional digits.
 * - "N%" -> "N процентов" (genitive plural form covers most cases).
 * - "N°C" -> "N градусов Цельсия".
 * - Ordinal-looking suffixes ("1-й", "2-я") are left alone — they're
 *   genuinely ambiguous without morphological context.
 *
 * Not in scope (intentionally — would need a morphological analyzer):
 * - General morphology beyond a small set of common counted nouns.
 * - Case agreement (always emits nominative).
 * - Phone numbers, IBANs, ranges with hyphens beyond simple "10-15".
 *
 */
class RussianNumberNormalizer {

    // All regexes used by normalize() compiled once per instance — they used
    // to be inlined inside the function, costing ~7 fresh compiles per call.
    // The TTS service hits normalize() once per Russian sentence, so on a
    // 1000-sentence audiobook we were burning ~7000 redundant compiles.
    private val rangeRegex = Regex("(?<![\\p{L}\\d_])(\\d+)\\s*[-–—]\\s*(\\d+)(?![\\p{L}\\d_])")
    private val percentRegex = Regex("(?<![\\p{L}\\d_])(\\d+(?:[,.]\\d+)?)\\s*%")
    private val celsiusRegex = Regex("(-?\\d+(?:[,.]\\d+)?)\\s*°\\s*[CС](?![\\p{L}\\d_])")
    private val degreesRegex = Regex("(-?\\d+(?:[,.]\\d+)?)\\s*°")
    private val groupedIntegerRegex = Regex("(?<![\\p{L}\\d])(-?\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+)(?![\\p{L}\\d]|[.,]\\d)")
    private val decimalRegex = Regex("(?<![\\p{L}\\d.,])(-?\\d{1,12})[,.](\\d{1,9})(?![\\p{L}\\d]|[.,]\\d)")
    private val integerRegex = Regex("(?<![\\p{L}\\d])(?<!\\d[.,])(-?\\d{1,12})(?![\\p{L}\\d]|[.,]\\d|-\\p{L})")
    private val whitespaceRegex = Regex("\\s+")
    private val nextNoun = Regex("^\\s+([+\\p{L}\\p{M}]+)")
    private val feminineNouns = setOf("запись", "записи", "страница", "страницы", "книга", "книги",
        "минута", "минуты", "секунда", "секунды", "строка", "строки", "глава", "главы",
        "женщина", "женщины", "девушка", "девушки", "копейка", "копейки")
    private val neuterNouns = setOf("имя", "имени", "окно", "окна", "слово", "слова", "письмо", "письма",
        "сообщение", "сообщения", "предложение", "предложения", "место", "места")

    data class LlmNumbers(val text: String, val ranges: List<IntRange>)

    private val masculineInA = setOf("мужчина", "мужчины", "юноша", "юноши", "папа", "папы", "дядя", "дяди", "дедушка", "дедушки",
        "судья", "судьи", "слуга", "слуги", "староста", "старосты", "воевода", "воеводы")
    private val feminineSoft = setOf("ночь", "дверь", "жизнь", "смерть", "любовь", "мышь", "площадь", "тетрадь", "вещь", "память",
        "новость", "кость", "часть", "мысль", "степень", "дочь", "мать", "сеть", "роль", "цель", "соль", "боль", "ель", "осень")

    /** Feminine counted noun in an oblique case ("из одной записи", "к одной книге", "с одной тетрадью"). */
    private fun feminineIn(following: String, case: RussianBookNormalizer.Case): Boolean {
        val noun = nextNoun.find(following)?.groupValues?.get(1)?.replace("+", "")?.replace("\u0301", "")?.lowercase().orEmpty()
        if (noun.isEmpty() || noun in masculineInA) return false
        if (noun in feminineNouns || feminineSoft.any { noun.startsWith(it.dropLast(1)) && noun.length <= it.length + 2 }) return true
        return when (case) {
            RussianBookNormalizer.Case.GEN -> noun.endsWith("ы") || noun.endsWith("и")
            RussianBookNormalizer.Case.DAT -> noun.endsWith("е")
            RussianBookNormalizer.Case.INS -> noun.endsWith("ой") || noun.endsWith("ей") || noun.endsWith("ью")
            RussianBookNormalizer.Case.PRE -> noun.endsWith("и")
            else -> false
        }
    }

    /** Gender (and accusative) of the last number word from the counted noun's form: after 1 the noun is in the
     * nominative or accusative singular (книга, книгу, окно, день), after 2-4 in the genitive singular, where
     * feminine nouns end in -ы/-и and masculine or neuter ones in -а/-я (две тетради, два стола, два окна). */
    private fun countedInteger(value: Long, following: String): String {
        val noun = nextNoun.find(following)?.groupValues?.get(1)?.replace("+", "")?.replace("\u0301", "")?.lowercase().orEmpty()
        val words = spellInteger(value)
        val masculine = noun in masculineInA
        return when {
            noun.isEmpty() -> words
            words.endsWith("один") && (noun in feminineNouns || noun in feminineSoft || !masculine && !noun.endsWith("мя") && (noun.endsWith("а") || noun.endsWith("я"))) ->
                words.removeSuffix("один") + "одна"
            words.endsWith("один") && !masculine && (noun.endsWith("у") || noun.endsWith("ю")) && !noun.endsWith("ую") ->
                words.removeSuffix("один") + "одну"
            words.endsWith("один") && (noun in neuterNouns || noun.endsWith("о") || noun.endsWith("е") || noun.endsWith("мя")) ->
                words.removeSuffix("один") + "одно"
            words.endsWith("два") && (noun in feminineNouns || !masculine && noun !in neuterNouns && !noun.endsWith("мени") &&
                (noun.endsWith("ы") || noun.endsWith("и"))) ->
                words.removeSuffix("два") + "две"
            else -> words
        }
    }

    /** Expand dates, years and plain counted integers before LLM, retaining exact numeric spans.
     * Dates and years arrive already in their case ([RussianDates]); other compound formats
     * (times, decimals, degrees, ranges, ordinals) remain digits for their downstream handlers.
     */
    fun prepareForLlm(text: String): LlmNumbers {
        val grouped = groupedIntegerRegex.replace(text) { m -> m.value.filterNot { it == ' ' || it == '\u00A0' || it == '\u202F' } }
        val dates = RussianDates.expand(grouped)
        val source = dates.text
        val out = StringBuilder()
        val spans = mutableListOf<IntRange>()
        var start = 0
        var nextDate = 0
        // Date words contain no digits; carry their spans over by the growth of earlier integers.
        fun copyDatesBefore(end: Int) {
            while (nextDate < dates.ranges.size && dates.ranges[nextDate].first < end) {
                val r = dates.ranges[nextDate++]; val shift = out.length - start
                spans += (r.first + shift)..(r.last + shift)
            }
        }
        for (match in integerRegex.findAll(source)) {
            val before = source.getOrNull(match.range.first - 1)
            val after = source.substring(match.range.last + 1)
            if (before in listOf(':', '/', '-', '–', '—') || after.trimStart().firstOrNull() in listOf('%', '°', ':', '/', '-', '–', '—')) continue
            val value = match.value.toLongOrNull() ?: continue
            copyDatesBefore(match.range.first)
            out.append(source, start, match.range.first)
            val numberStart = out.length
            // A governing preposition decides the case, as on the offline path ("в одной тысяче пятистах метрах",
            // "без пяти минут"); the LLM may still adjust the span. Otherwise gender comes from the counted noun.
            val case = RussianBookNormalizer.inferredCase(source.substring(maxOf(0, match.range.first - 40), match.range.first), after)
            out.append(if (case != RussianBookNormalizer.Case.NOM && value >= 0)
                RussianBookNormalizer.cardinal(value, case, feminine = feminineIn(after, case)) else countedInteger(value, after))
            spans += numberStart until out.length
            start = match.range.last + 1
        }
        copyDatesBefore(source.length)
        out.append(source, start, source.length)
        return LlmNumbers(out.toString(), spans)
    }

    private val units = arrayOf(
        "ноль", "один", "два", "три", "четыре", "пять", "шесть",
        "семь", "восемь", "девять"
    )
    private val unitsFeminine = arrayOf(
        "ноль", "одна", "две", "три", "четыре", "пять", "шесть",
        "семь", "восемь", "девять"
    )
    private val teens = arrayOf(
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
        "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"
    )
    private val tens = arrayOf(
        "", "", "двадцать", "тридцать", "сорок", "пятьдесят",
        "шестьдесят", "семьдесят", "восемьдесят", "девяносто"
    )
    private val hundreds = arrayOf(
        "", "сто", "двести", "триста", "четыреста",
        "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот"
    )

    /** Standard Russian rule: 1 form for 1, 21, 31… (but not 11); 2/3/4 form for 2-4, 22-24…; "many" form otherwise. */
    private fun pluralForm(n: Long, one: String, few: String, many: String): String {
        val abs = (if (n < 0) -n else n) % 100
        if (abs in 11..14) return many
        return when (abs % 10) {
            1L -> one
            2L, 3L, 4L -> few
            else -> many
        }
    }

    /** Spell numbers 0..999 with optional feminine inflection for the units digit. */
    private fun spellTriad(n: Int, feminine: Boolean): String {
        if (n == 0) return ""
        val parts = mutableListOf<String>()
        val h = n / 100
        val rest = n % 100
        if (h > 0) parts.add(hundreds[h])
        if (rest in 10..19) {
            parts.add(teens[rest - 10])
        } else {
            val t = rest / 10
            val u = rest % 10
            if (t > 0) parts.add(tens[t])
            if (u > 0) parts.add(if (feminine) unitsFeminine[u] else units[u])
        }
        return parts.joinToString(" ")
    }

    /**
     * Converts a non-negative integer up to 10^12 - 1 into words.
     *
     * "Тысяча" is grammatically feminine, so the units in the thousands
     * triad use одна/две (not один/два). "Миллион" and "миллиард" are
     * masculine — units stay один/два.
     */
    fun spellInteger(value: Long): String {
        if (value == 0L) return units[0]

        var n = if (value < 0) -value else value
        val parts = mutableListOf<String>()
        if (value < 0) parts.add("минус")

        val billions = (n / 1_000_000_000L).toInt(); n %= 1_000_000_000L
        val millions = (n / 1_000_000L).toInt();      n %= 1_000_000L
        val thousands = (n / 1_000L).toInt();         n %= 1_000L
        val units1to999 = n.toInt()

        if (billions > 0) {
            parts.add(spellTriad(billions, feminine = false))
            parts.add(pluralForm(billions.toLong(), "миллиард", "миллиарда", "миллиардов"))
        }
        if (millions > 0) {
            parts.add(spellTriad(millions, feminine = false))
            parts.add(pluralForm(millions.toLong(), "миллион", "миллиона", "миллионов"))
        }
        if (thousands > 0) {
            parts.add(spellTriad(thousands, feminine = true))
            parts.add(pluralForm(thousands.toLong(), "тысяча", "тысячи", "тысяч"))
        }
        if (units1to999 > 0) {
            parts.add(spellTriad(units1to999, feminine = false))
        }

        return whitespaceRegex.replace(parts.joinToString(" ").trim(), " ")
    }

    /**
     * Decimal: "3,14" -> "три целых четырнадцать сотых".
     * Fractional digits are also read as one number, up to billionths.
     */
    fun spellDecimal(integerPart: Long, fractional: String): String {
        val intWords = spellInteger(integerPart)
        val intSuffix = pluralForm(integerPart, "целая", "целых", "целых")
        if (fractional.length in 1..9 && fractional.all { it.isDigit() }) {
            val fracValue = fractional.toLong()
            val fracWords = spellIntegerFeminine(fracValue)
            // Use feminine for the integer triad here too (целая is feminine).
            val intWordsFem = spellIntegerFeminine(integerPart)
            val denominators = arrayOf("десятая", "сотая", "тысячная", "десятитысячная",
                "стотысячная", "миллионная", "десятимиллионная", "стомиллионная", "миллиардная")
            val denominator = denominators[fractional.length - 1]
            val plural = denominator.removeSuffix("ая") + "ых"
            val denomWord = pluralForm(fracValue, denominator, plural, plural)
            return whitespaceRegex
                .replace("$intWordsFem ${pluralForm(integerPart, "целая", "целых", "целых")} $fracWords $denomWord", " ")
                .trim()
        }
        // Fall back: digit-by-digit reading for the fractional tail.
        val tail = fractional.map { digit ->
            if (digit.isDigit()) units[digit - '0'] else digit.toString()
        }.joinToString(" ")
        return "$intWords $intSuffix $tail"
    }

    /** Same as spellInteger, but the trailing units triad uses feminine forms (for "целая"). */
    private fun spellIntegerFeminine(value: Long): String {
        if (value == 0L) return unitsFeminine[0]
        var n = if (value < 0) -value else value
        val parts = mutableListOf<String>()
        if (value < 0) parts.add("минус")

        val billions = (n / 1_000_000_000L).toInt(); n %= 1_000_000_000L
        val millions = (n / 1_000_000L).toInt();      n %= 1_000_000L
        val thousands = (n / 1_000L).toInt();         n %= 1_000L
        val units1to999 = n.toInt()

        if (billions > 0) {
            parts.add(spellTriad(billions, feminine = false))
            parts.add(pluralForm(billions.toLong(), "миллиард", "миллиарда", "миллиардов"))
        }
        if (millions > 0) {
            parts.add(spellTriad(millions, feminine = false))
            parts.add(pluralForm(millions.toLong(), "миллион", "миллиона", "миллионов"))
        }
        if (thousands > 0) {
            parts.add(spellTriad(thousands, feminine = true))
            parts.add(pluralForm(thousands.toLong(), "тысяча", "тысячи", "тысяч"))
        }
        if (units1to999 > 0) {
            parts.add(spellTriad(units1to999, feminine = true))
        }
        return whitespaceRegex.replace(parts.joinToString(" ").trim(), " ")
    }

    /**
     * Walks the input text and replaces numeric tokens. Order of substitutions
     * matters: percent / degree handlers must run before the general number
     * rule, otherwise "15%" becomes "15 percent of …" then the "15" gets
     * spelled out separately.
     */
    fun normalize(text: String): String {
        var t = groupedIntegerRegex.replace(text) { m ->
            m.value.filterNot { it == ' ' || it == '\u00A0' || it == '\u202F' }
        }

        // Range: "10-15" / "10—15" -> "от 10 до 15" (then numbers spelled out below).
        t = rangeRegex.replace(t) { m ->
            "от ${m.groupValues[1]} до ${m.groupValues[2]}"
        }

        // Percent: "15%" or "15 %"
        t = percentRegex.replace(t) { m ->
            "${m.groupValues[1]} процентов"
        }

        // Degrees Celsius: "−5°C" or "5 °C"
        t = celsiusRegex.replace(t) { m ->
            "${m.groupValues[1]} градусов Цельсия"
        }
        t = degreesRegex.replace(t) { m ->
            "${m.groupValues[1]} градусов"
        }

        // Decimals with comma or point: "3,14" / "3.14".
        t = decimalRegex.replace(t) { m ->
            val sign = if (m.groupValues[1].startsWith("-")) { "минус " } else ""
            val intPart = m.groupValues[1].trimStart('-').toLongOrNull() ?: return@replace m.value
            val frac = m.groupValues[2]
            "$sign${spellDecimal(intPart, frac)}"
        }

        // Plain integers — last, after compound forms above have already
        // rewritten themselves into "<number> <unit>".
        t = integerRegex.replace(t) { m ->
            val raw = m.groupValues[1]
            val n = raw.toLongOrNull() ?: return@replace raw
            countedInteger(n, t.substring(m.range.last + 1))
        }

        return whitespaceRegex.replace(t, " ").trim()
    }
}
