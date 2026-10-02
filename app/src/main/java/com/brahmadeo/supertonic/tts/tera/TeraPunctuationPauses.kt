package com.brahmadeo.supertonic.tts.tera

import kotlin.math.abs

/** Audible punctuation boundaries, independent of whether LLM preparation is enabled. */
object TeraPunctuationPauses {
    data class Part(val text: String, val pauseMs: Int)
    private val abbreviations = setOf("г", "гг", "ул", "д", "кв", "стр", "им", "рис", "см", "напр", "др", "руб", "коп", "тыс", "млн", "млрд", "т", "е", "н", "п")
    private fun abbreviationDot(text: String, index: Int): Boolean {
        var next = index + 1
        while (next < text.length && text[next].isWhitespace()) next++
        if (next == text.length) return false // Retain the breath at a paragraph end.
        var start = index
        while (start > 0 && text[start - 1].isLetter()) start--
        val token = text.substring(start, index)
        val lower = token.lowercase()
        if (lower in abbreviations && text[next].isUpperCase() && lower !in setOf("г", "гг", "ул", "им", "рис", "см")) return false
        return lower in abbreviations ||
            (token.length == 1 && token[0].isUpperCase() && text[next].isUpperCase())
    }

    fun split(text: String, commaMs: Int = 180, sentenceMs: Int = 420): List<Part> {
        val result = mutableListOf<Part>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val numeric = i > 0 && i + 1 < text.length && text[i - 1].isDigit() && text[i + 1].isDigit()
            val pause = when {
                numeric -> 0
                c == ',' -> commaMs
                c in ";:" -> commaMs + 80
                (c in "—–" || (c == '-' && i > 0 && i + 1 < text.length && text[i - 1].isWhitespace() && text[i + 1].isWhitespace())) &&
                    text.substring(start, i).any { it.isLetterOrDigit() } -> commaMs + 40
                c in ".!?…" -> {
                    // Keep dots inside abbreviations and dates intact.
                    if (c == '.' && ((i + 1 < text.length && text[i + 1].isLetterOrDigit()) || abbreviationDot(text, i))) 0 else sentenceMs
                }
                else -> 0
            }
            if (pause > 0) {
                var end = i + 1
                while (end < text.length && text[end] in ".!?…\"'«»“”„)]") end++
                val part = text.substring(start, end).trim()
                if (part.any { it.isLetterOrDigit() }) result += Part(part, pause)
                else if (result.isNotEmpty()) {
                    val previous = result.removeAt(result.lastIndex)
                    result += Part(previous.text + part, maxOf(previous.pauseMs, pause))
                }
                start = end
                i = end
            } else i++
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty()) result += Part(tail, 0)
        return result
    }

    /** Add only the missing silence; retain natural model pauses already present. */
    fun missingSilenceSamples(pcm: ByteArray, pauseMs: Int, sampleRate: Int = 44100): Int {
        var silentSamples = 0
        var offset = pcm.size - 2
        while (offset >= 0) {
            val sample = ((pcm[offset].toInt() and 255) or (pcm[offset + 1].toInt() shl 8)).toShort().toInt()
            if (abs(sample) > 160) break
            silentSamples++
            offset -= 2
        }
        return (sampleRate.toLong() * pauseMs / 1000 - silentSamples).coerceAtLeast(0).toInt()
    }
}
