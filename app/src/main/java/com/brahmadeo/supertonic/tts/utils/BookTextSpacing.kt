package com.brahmadeo.supertonic.tts.utils

/** Book typography must not turn word separators into discarded model symbols. */
object BookTextSpacing {
    private val punctuation = Regex("[,.!?;:…](?=[\\p{L}\\p{N}])")
    private val links = Regex("(?:https?://|www\\.)[^\\s]+|[\\p{L}\\d._%+-]+@[\\p{L}\\d.-]+\\.[\\p{L}]{2,}|(?:[\\p{L}\\d-]+\\.)+(?:com|org|net|ru|cz|io|ai|dev|edu|gov)(?:/[^\\s]*)?",RegexOption.IGNORE_CASE)
    fun unusualSpaceCount(text: String) = text.count {
        (it.isWhitespace() || Character.isSpaceChar(it) || it == '\u200b') && it !in " \t\n\r"
    }
    fun normalize(text: String): String {
        val spaced = text.map { c -> when {
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
