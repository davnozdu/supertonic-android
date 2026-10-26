package com.brahmadeo.supertonic.tts.utils

/** «Скобки» in «Звук и паузы»: how text in round or square brackets is read, for every model and with or without
 * the LLM (applied in [RussianBookNormalizer.normalize], which both paths run). Models themselves drop brackets.
 * Footnote marks («[1]», «(12)», «(*)») are never read; an unpaired bracket («1) пункт») is dropped. */
object BracketReading {
    const val KEY = "bracket_reading"
    enum class Mode { WORDS, PAUSE, ASIDE, OFF }
    /** Set from the preference by [SpeechPreparationCache], which also clears the caches when it changes. */
    @Volatile var mode = Mode.WORDS

    fun from(value: String?) = Mode.entries.firstOrNull { it.name == value } ?: Mode.WORDS

    private val footnote = Regex("[(\\[]\\s*(?:\\d{1,4}|\\*{1,3})\\s*[)\\]]")
    private val pair = Regex("\\(([^()\\[\\]]{1,400})\\)|\\[([^()\\[\\]]{1,400})]")

    fun apply(text: String, mode: Mode = this.mode): String {
        if (mode == Mode.OFF || (text.indexOf('(') < 0 && text.indexOf('[') < 0)) return text
        val noNotes = footnote.replace(text, "")
        val replaced = pair.replace(noNotes) { m ->
            val inner = (m.groups[1] ?: m.groups[2])!!.value.trim()
            if (inner.none { it.isLetterOrDigit() }) " " else when (mode) {
                Mode.WORDS -> ", скобка открывается, $inner, скобка закрывается, "
                // Kept as "(…)" for SupertonicTTS to voice as its own phrase; a short one ("(с)", "(ок)") stays inline.
                Mode.ASIDE -> if (inner.count { it.isLetter() } >= ASIDE_MIN_LETTERS) " ($inner) " else " $inner "
                else -> ", $inner, "
            }
        }
        return if (replaced == text) text else tidy(replaced)
    }

    const val ASIDE_MIN_LETTERS = 4
    const val ASIDE_PAUSE_MS = 250
    const val ASIDE_SPEED = 1.08f
    const val ASIDE_GAIN = 0.85f
    data class Segment(val text: String, val aside: Boolean)
    private val asidePair = Regex("\\(([^()]+)\\)")

    /** [Mode.ASIDE]: the text split into plain runs and asides, each aside voiced as its own phrase with a pause
     * around it, slightly faster and quieter, the way a narrator reads a parenthesis. From the TeraTTS fork
     * (github.com/9208499-ship-it/teratts-android, rust/src/tera.rs split_asides). */
    fun asides(text: String): List<Segment> {
        val result = mutableListOf<Segment>(); var at = 0
        for (m in asidePair.findAll(text)) {
            val inner = m.groupValues[1].trim()
            if (inner.count { it.isLetter() } < ASIDE_MIN_LETTERS) continue
            text.substring(at, m.range.first).trim().takeIf { it.any(Char::isLetterOrDigit) }?.let { result += Segment(it, false) }
            result += Segment(inner, true)
            at = m.range.last + 1
        }
        if (result.isEmpty()) return listOf(Segment(text, false))
        text.substring(at).trim().takeIf { it.any(Char::isLetterOrDigit) }?.let { result += Segment(it, false) }
        return result
    }

    /** Commas the replacement put next to existing punctuation or at the edges. */
    private fun tidy(text: String): String {
        var t = text
        t = t.replace(Regex("[ \\t]+(?=[,.!?…;:])"), "")
        t = t.replace(Regex(",(?:\\s*,)+"), ",")
        t = t.replace(Regex(",\\s*([.!?…;:])"), "$1")
        t = t.replace(Regex("([.!?…;:—–-])\\s*,"), "$1")
        t = t.replace(Regex("^\\s*,\\s*"), "")
        t = t.replace(Regex(",\\s*$"), "")
        t = t.replace(Regex(",(?=\\S)"), ", ")
        return t.replace(Regex("[ \\t]{2,}"), " ").trim()
    }
}
