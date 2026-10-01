package com.brahmadeo.supertonic.tts.tera

/** Converts book punctuation to characters present in Tera's vocabulary. */
internal object TeraTextPreparation {
    private val spacedDash = Regex("[—]|(?<=\\s)[–-](?=\\s)|^[–-](?=\\s)")
    private val punctuationNeedsSpace = Regex("[,.!?;:](?=[\\p{L}\\p{N}])")

    fun punctuation(text: String): String {
        var prepared = text.replace("…", "...")
            .replace('“', '"').replace('”', '"')
            .replace('‘', '\'').replace('’', '\'')
            .replace('‑', '-').replace('\u00a0', ' ')
        prepared = spacedDash.replace(prepared) { match ->
            val prefix = prepared.substring(0, match.range.first)
            val before = prefix.trimEnd(' ', '\t', '«', '"')
            // A dialogue marker or a dash after punctuation must not add
            // another comma. An internal dash supplies a supported pause cue.
            if (before.isEmpty() || prefix.substringAfterLast('\n').isBlank() ||
                before.last() in ".,!?;:\n") " " else ", "
        }.replace('–', '-')
        return punctuationNeedsSpace.replace(prepared) { match ->
            val index = match.range.first
            val mark = match.value[0]
            if ((mark == '.' || mark == ',') && index > 0 && index + 1 < prepared.length &&
                prepared[index - 1].isDigit() && prepared[index + 1].isDigit()) match.value
            else "${match.value} "
        }.replace(Regex("[ \\t]+"), " ").trim()
    }
}
