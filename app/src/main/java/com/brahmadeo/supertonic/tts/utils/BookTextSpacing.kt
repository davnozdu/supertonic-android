package com.brahmadeo.supertonic.tts.utils

/** Book typography must not turn word separators into discarded model symbols. */
object BookTextSpacing {
    private val punctuation = Regex("[,.!?;:…](?=[\\p{L}\\p{N}])")
    private val links = Regex("(?:https?://|www\\.)[^\\s]+|[\\p{L}\\d._%+-]+@[\\p{L}\\d.-]+\\.[\\p{L}]{2,}|(?:[\\p{L}\\d-]+\\.)+(?:com|org|net|ru|cz|io|ai|dev|edu|gov)(?:/[^\\s]*)?",RegexOption.IGNORE_CASE)
    fun unusualSpaceCount(text: String) = text.count {
        (it.isWhitespace() || Character.isSpaceChar(it) || it == '\u200b') && it !in " \t\n\r"
    }
    /** Invisible format characters of book files: a soft hyphen («мо\u00ADлоко») split a word for the stress
     * check, the names and Silero (and became a space in its voice); a BOM or a joiner made the LLM's answer fail. */
    private const val INVISIBLE = "\u00AD\uFEFF\u2060\u200C\u200D\u180E"
    private val wordSpace = Regex("(?<=\\p{L})\u200B(?=\\p{L})")
    private val graveStress = Regex("([аеёиоуыэюяАЕЁИОУЫЭЮЯ])\u0300")
    private val token = Regex("[\\p{L}\\p{M}]+")
    /** Latin letters typed inside a Russian word: look-alikes, and accented vowels used as stress marks («молокó»). */
    private val latin = mapOf('a' to "а", 'e' to "е", 'o' to "о", 'p' to "р", 'c' to "с", 'x' to "х", 'y' to "у", 'k' to "к",
        'A' to "А", 'B' to "В", 'E' to "Е", 'K' to "К", 'M' to "М", 'H' to "Н", 'O' to "О", 'P' to "Р", 'C' to "С", 'T' to "Т",
        'X' to "Х", 'Y' to "У", 'á' to "а\u0301", 'é' to "е\u0301", 'ó' to "о\u0301", 'ý' to "у\u0301", 'à' to "а\u0301",
        'è' to "е\u0301", 'ò' to "о\u0301", 'Á' to "А\u0301", 'É' to "Е\u0301", 'Ó' to "О\u0301", 'Ý' to "У\u0301", 'ë' to "ё", 'Ë' to "Ё")
    private fun cyrillic(c: Char) = c in 'А'..'я' || c == 'ё' || c == 'Ё'
    /** One spelling of the same text for every step: invisible characters removed, letters composed (е + ◌̈ = ё,
     * и + ◌̆ = й), a grave stress mark made acute, Latin letters inside a Cyrillic word made Cyrillic. Foreign words
     * (only Latin letters) stay as they are: they go to the other TTS engine. */
    fun canonical(text: String): String {
        var t = if (text.any { it in INVISIBLE }) text.filterNot { it in INVISIBLE } else text
        if ('\u200B' in t) t = wordSpace.replace(t, "")
        if (t.any { it.code in 0x300..0x36F || it in "ѐѝЀЍ" }) {
            t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFC)
                .replace("ѐ", "е\u0301").replace("ѝ", "и\u0301").replace("Ѐ", "Е\u0301").replace("Ѝ", "И\u0301")
            t = graveStress.replace(t) { it.groupValues[1] + "\u0301" }
        }
        if (t.any { it in latin }) t = token.replace(t) { m ->
            val w = m.value
            if (w.none(::cyrillic) || w.any { it.isLetter() && !cyrillic(it) && it !in latin }) w
            else buildString { for (c in w) append(latin[c] ?: c.toString()) }
        }
        return t
    }
    fun normalize(text: String): String {
        val spaced = canonical(text).map { c -> when {
            c == '\n' || c == '\r' -> c
            c.isWhitespace() || Character.isSpaceChar(c) || c == '\u200b' || c == '\u0085' -> ' '
            c in "\u2010\u2011\u2012\u2043\u2212\uFE63\uFF0D" -> '-'
            else -> c
        } }.joinToString("")
        val protected = links.findAll(spaced).map { it.range }.toList()
        return punctuation.replace(spaced) { match ->
            val i=match.range.first
            if(protected.any { i in it } || (match.value[0] in ".,:" && i>0 && spaced[i-1].isDigit() && spaced[i+1].isDigit())) match.value
            else "${match.value} "
        }
    }
}
