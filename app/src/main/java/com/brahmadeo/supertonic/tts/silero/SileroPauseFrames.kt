package com.brahmadeo.supertonic.tts.silero

/** Both released Silero packs keep ten natural frames before their fixed silence. */
internal object SileroPauseFrames {
    const val EVERY_COMMA_KEY = "silero_every_comma"
    fun forPunctuation(c: Char, commaMs: Int): Long? {
        if(commaMs<=0 || c !in ",;:–—") return null
        val ms=commaMs + if(c==',') 0 else 80
        return 10L + kotlin.math.round(ms/12.5).toLong()
    }

    /** Whether the comma at [index] of the prepared text separates whole parts of the phrase, where a reader
     * pauses. Russian puts commas where speech flows on: after an introductory word or an address («Впрочем,»
     * «Послушай,»), between one-word list items («хлеб, воду, нож»), before «что/чтобы/как» after a short main
     * clause («Я знал, что…»). A fixed pause there sounds like stumbling, so those keep the model's own rhythm. */
    fun commaNeedsPause(text: String, index: Int): Boolean {
        if (text.getOrNull(index) != ',') return false
        val left = words(text.substring(boundaryBefore(text, index), index))
        val right = words(text.substring(index + 1, boundaryAfter(text, index)))
        if (left.size <= 1 || right.size <= 1) return false
        if (left.size <= 2 && right.size <= 2) return false
        if (right.first() in subordinators && left.size <= 3) return false
        if (left.joinToString(" ") in introductory || right.joinToString(" ") in introductory) return false
        return true
    }
    private val boundaries = ",.;:!?…"
    private fun boundaryBefore(text: String, index: Int): Int {
        for (i in index - 1 downTo 0) if (text[i] in boundaries) return i + 1
        return 0
    }
    private fun boundaryAfter(text: String, index: Int): Int {
        for (i in index + 1 until text.length) if (text[i] in boundaries) return i
        return text.length
    }
    private fun words(part: String) = part.replace("+", "").split(' ').filter { w -> w.any { it.isLetter() } }
    private val subordinators = setOf("что", "чтобы", "как", "будто", "словно", "ли")
    private val introductory = setOf("может быть", "во первых", "во вторых", "в третьих", "к счастью", "к сожалению",
        "кроме того", "по моему", "по твоему", "так сказать", "честно говоря", "в общем", "по правде", "само собой",
        "конечно же", "надо сказать", "иначе говоря", "другими словами", "с другой", "с одной стороны", "с другой стороны")
}
