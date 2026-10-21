package com.brahmadeo.supertonic.tts.utils

/** «Скобки» in «Звук и паузы»: how text in round or square brackets is read, for every model and with or without
 * the LLM (applied in [RussianBookNormalizer.normalize], which both paths run). Models themselves drop brackets.
 * Footnote marks («[1]», «(12)», «(*)») are never read; an unpaired bracket («1) пункт») is dropped. */
object BracketReading {
    const val KEY = "bracket_reading"
    enum class Mode { WORDS, PAUSE, OFF }
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
                else -> ", $inner, "
            }
        }
        return if (replaced == text) text else tidy(replaced)
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
