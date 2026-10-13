package com.brahmadeo.supertonic.tts.utils

/** Whether the word at [index] opens a sentence: only spaces, quotes, brackets and dashes between it and the start
 * of the text, a line break, or `. ! ? … : ;`. One rule for the names dictionary, the names memory, Silero's
 * «Семена» and the stress check («— Семена взошли», «Он сказал: «Семена…» open sentences too). */
object SentenceStart {
    private const val SKIP = "«»\"„“”‚‘’‹›'()[]—–-"
    fun at(text: String, index: Int): Boolean {
        var i = index - 1
        while (i >= 0 && (text[i].isWhitespace() || text[i] in SKIP)) { if (text[i] == '\n') return true; i-- }
        return i < 0 || text[i] in ".!?…:;"
    }
}
