package com.brahmadeo.supertonic.tts.silero

/** Both released Silero packs keep ten natural frames before their fixed silence. */
internal object SileroPauseFrames {
    fun forPunctuation(c: Char, commaMs: Int): Long? {
        if(commaMs<=0 || c !in ",;:–—") return null
        val ms=commaMs + if(c==',') 0 else 80
        return 10L + kotlin.math.round(ms/12.5).toLong()
    }
}
