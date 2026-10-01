package com.brahmadeo.supertonic.tts.tera

import java.util.Locale

/** Converts book punctuation to characters present in Tera's vocabulary. */
internal object TeraTextPreparation {
    private val wordPattern = Regex("[+А-Яа-яЁё]+")
    private val acute = Regex("([АЕЁИОУЫЭЮЯаеёиоуыэюя])\\u0301")
    private val spacedDash = Regex("[ \\t]*—[ \\t]*|[ \\t]+[–-][ \\t]+|^[–-][ \\t]+", RegexOption.MULTILINE)
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

    fun stress(text: String, lookup: (String) -> String?, yoWords: Map<String, String>,
               ambiguousStress: Set<String>, ambiguousYo: Set<String>): String {
        val manual = acute.replace(text) { "+${it.groupValues[1]}" }
        return wordPattern.replace(manual) { match ->
            val original = match.value
            if ('+' in original) return@replace original
            val key = original.lowercase(Locale.ROOT)
            val replacement = if (key in ambiguousYo) null else yoWords[key]
            val yo = replacement?.mapIndexed { i, c ->
                if (original.getOrNull(i)?.isUpperCase() == true) c.uppercaseChar() else c
            }?.joinToString("") ?: original
            val normalized = yo.lowercase(Locale.ROOT)
            if (normalized in ambiguousStress) return@replace yo
            val marked = lookup(normalized) ?: return@replace yo
            if (marked.replace("+", "").length != yo.length) return@replace yo
            val out = StringBuilder()
            var index = 0
            for (c in marked) {
                if (c == '+') out.append('+') else out.append(yo[index++])
            }
            out.toString()
        }
    }
}
