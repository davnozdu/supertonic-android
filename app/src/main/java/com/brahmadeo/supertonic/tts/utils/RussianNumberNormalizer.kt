package com.brahmadeo.supertonic.tts.utils

import java.util.regex.Pattern

/**
 * Russian number-to-words converter for TTS pre-processing.
 *
 * Goal: stop the model from spelling digits out one-by-one
 * ("два ноль два пять") when it sees "2025" inside Russian text.
 *
 * Scope:
 * - Integers up to 10^12 with correct тысяч/миллион/миллиард agreement.
 * - Decimal numbers with a comma: "3,14" -> "три целых четырнадцать сотых"
 *   for short decimals, otherwise digit-by-digit after the comma.
 * - "N%" -> "N процентов" (genitive plural form covers most cases).
 * - "N°C" -> "N градусов Цельсия".
 * - Ordinal-looking suffixes ("1-й", "2-я") are left alone — they're
 *   genuinely ambiguous without morphological context.
 *
 * Not in scope (intentionally — would need a morphological analyzer):
 * - Gender agreement of "один/одна/одно" with the following noun.
 * - Case agreement (always emits nominative).
 * - Phone numbers, IBANs, ranges with hyphens beyond simple "10-15".
 *
 * Practical tradeoff: in 95% of book/article text the nominative reading
 * sounds natural; in legal/technical text with heavy case agreement the
 * model already gets some words wrong regardless.
 */
class RussianNumberNormalizer {

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

        return parts.joinToString(" ").trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Decimal: "3,14" -> "три целых четырнадцать сотых"; for longer fractional
     * parts we fall back to "три целых один четыре один пять девять два шесть"
     * to avoid extremely awkward agreement.
     */
    fun spellDecimal(integerPart: Long, fractional: String): String {
        val intWords = spellInteger(integerPart)
        val intSuffix = pluralForm(integerPart, "целая", "целых", "целых")
        if (fractional.length in 1..2 && fractional.all { it.isDigit() }) {
            val fracValue = fractional.toLong()
            val fracWords = spellInteger(fracValue)
            // Use feminine for the integer triad here too (целая is feminine).
            val intWordsFem = spellIntegerFeminine(integerPart)
            val denomWord = if (fractional.length == 1) {
                pluralForm(fracValue, "десятая", "десятых", "десятых")
            } else {
                pluralForm(fracValue, "сотая", "сотых", "сотых")
            }
            return "$intWordsFem ${pluralForm(integerPart, "целая", "целых", "целых")} $fracWords $denomWord"
                .replace(Regex("\\s+"), " ")
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
        return parts.joinToString(" ").trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Walks the input text and replaces numeric tokens. Order of substitutions
     * matters: percent / degree handlers must run before the general number
     * rule, otherwise "15%" becomes "15 percent of …" then the "15" gets
     * spelled out separately.
     */
    fun normalize(text: String): String {
        var t = text

        // Range: "10-15" / "10—15" -> "от 10 до 15" (then numbers spelled out below).
        t = Regex("\\b(\\d+)\\s*[-–—]\\s*(\\d+)\\b").replace(t) { m ->
            "от ${m.groupValues[1]} до ${m.groupValues[2]}"
        }

        // Percent: "15%" or "15 %"
        t = Regex("\\b(\\d+(?:[,.]\\d+)?)\\s*%").replace(t) { m ->
            val numStr = m.groupValues[1]
            "${numStr} процентов"
        }

        // Degrees Celsius: "−5°C" or "5 °C"
        t = Regex("(-?\\d+(?:[,.]\\d+)?)\\s*°\\s*[CС]\\b").replace(t) { m ->
            "${m.groupValues[1]} градусов Цельсия"
        }
        t = Regex("(-?\\d+(?:[,.]\\d+)?)\\s*°").replace(t) { m ->
            "${m.groupValues[1]} градусов"
        }

        // Decimals with comma: "3,14"
        t = Regex("(?<![\\p{L}\\d])(-?\\d+),(\\d+)(?!\\d)").replace(t) { m ->
            val sign = if (m.groupValues[1].startsWith("-")) { "минус " } else ""
            val intPart = m.groupValues[1].trimStart('-').toLongOrNull() ?: return@replace m.value
            val frac = m.groupValues[2]
            "$sign${spellDecimal(intPart, frac)}"
        }

        // Plain integers — last, after compound forms above have already
        // rewritten themselves into "<number> <unit>".
        t = Regex("(?<![\\p{L}\\d.,])(-?\\d{1,12})(?![\\p{L}\\d.,])").replace(t) { m ->
            val raw = m.groupValues[1]
            val n = raw.toLongOrNull() ?: return@replace raw
            spellInteger(n)
        }

        return t.replace(Regex("\\s+"), " ").trim()
    }
}
