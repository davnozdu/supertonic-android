package com.brahmadeo.supertonic.tts.foreign

import com.brahmadeo.supertonic.tts.utils.RussianNumberNormalizer

/** Script boundaries preserve the original text exactly, including punctuation. */
internal object ForeignText {
    data class Part(val text: String, val foreign: Boolean)
    private val words = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’–-][\\p{L}\\p{M}\\p{N}]+)*")
    fun split(text: String): List<Part> {
        val tokens = words.findAll(text).toList()
        val kinds = tokens.map { token -> when {
            token.value.any { it in 'Ѐ'..'ӿ' } -> false
            token.value.any { it.isLetter() } -> true
            else -> null
        } }
        var previous: Boolean? = null
        val resolved = kinds.mapIndexed { i, kind ->
            (kind ?: previous ?: kinds.drop(i + 1).firstOrNull { it != null } ?: false).also { previous = it }
        }
        if (tokens.isEmpty()) return listOf(Part(text, false))
        val result = mutableListOf<Part>(); var start = 0; var kind = resolved.first()
        tokens.forEachIndexed { i, token -> if (resolved[i] != kind) {
            result += Part(text.substring(start, token.range.first), kind)
            start = token.range.first; kind = resolved[i]
        } }
        result += Part(text.substring(start), kind)
        return result
    }
    fun language(text: String, requested: String, preference: String): String {
        if (preference != "auto") return preference
        val req = requested.lowercase().substringBefore('-').substringBefore('_')
        if (req !in setOf("ru", "rus", "")) return when (req) { "ces", "cze" -> "cs"; "eng" -> "en"; else -> req }
        val lower = text.lowercase()
        if (lower.any { it in "čďěňřšťůž" }) return "cs"
        val tokens = words.findAll(lower).map { it.value }.toList()
        val hints = setOf("jsem", "jsou", "prosim", "dobry", "ahoj", "neni", "dobré", "děkuji", "dobrý", "máte", "nebo")
        return if (tokens.any { it in hints }) "cs" else "en"
    }
    fun prepareNumbers(text: String, normalizer: RussianNumberNormalizer): RussianNumberNormalizer.LlmNumbers {
        val output = StringBuilder(); val ranges = mutableListOf<IntRange>()
        split(text).forEach { part ->
            if (part.foreign) output.append(part.text) else {
                val prepared = normalizer.prepareForLlm(part.text); val offset = output.length
                output.append(prepared.text)
                prepared.ranges.forEach { ranges += (it.first + offset)..(it.last + offset) }
            }
        }
        return RussianNumberNormalizer.LlmNumbers(output.toString(), ranges)
    }
    fun transliterate(text: String): String {
        val from = "abcdefghijklmnopqrstuvwxyzáčďéěíňóřššťúůýž"
        val to = listOf("а","б","к","д","е","ф","г","х","и","дж","к","л","м","н","о","п","к","р","с","т","у","в","в","кс","и","з",
            "а","ч","д","е","е","и","н","о","рж","ш","ш","т","у","у","и","ж")
        return text.lowercase().map { ch -> from.indexOf(ch).let { if (it >= 0) to[it] else ch.toString() } }.joinToString("")
    }
}
