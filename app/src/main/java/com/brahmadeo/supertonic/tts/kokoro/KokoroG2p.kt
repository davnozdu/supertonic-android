package com.brahmadeo.supertonic.tts.kokoro

/** Port of zaakirio/kokoro-ru ru_g2p.py (Apache-2.0), plus Misaki IPA mapping.
 * The common MyTTS preparation supplies stress. This frontend never restresses it. */
internal object KokoroG2p {
    private const val vowels = "аеёиоуыэюя"
    private val words = Regex("[а-яё\u0301]+")
    private val marks = Regex("([;:,.!?—…\"()“”])")
    private val exceptions = mapOf("дорого" to "дорога", "недорого" to "недорога", "настрого" to "настрога",
        "конечно" to "конешно", "скучно" to "скушно", "скучный" to "скушный", "нарочно" to "нарошно",
        "яичница" to "яишница", "скворечник" to "скворешник", "девичник" to "девишник",
        "двоечник" to "двоешник", "троечник" to "троешник", "прачечная" to "прашечная",
        "горчичник" to "горчишник", "пустячный" to "пустяшный")
    private val ogoBlacklist = setOf("много", "немного", "намного", "ненамного", "строго", "нестрого", "настрого",
        "дорого", "недорого", "полого", "убого", "лего", "диего", "ого", "огого")
    private val clusters = listOf("солнц" to "сонц", "чувств" to "чуств", "здравств" to "здраств",
        "счастлив" to "счаслив", "завистлив" to "завислив", "участлив" to "учаслив", "совестлив" to "совеслив")

    fun marked(text: String): String {
        val acute = Regex("\\+([аеёиоуыэюяАЕЁИОУЫЭЮЯ])").replace(text) { it.groupValues[1] + "\u0301" }
            .lowercase().replace('«', '“').replace('»', '”').replace('[', '(').replace('{', '(').replace(']', ')').replace('}', ')')
        return words.replace(acute) { match ->
            val word = match.value
            val bare = word.replace("\u0301", "")
            val ordinal = word.indexOf('\u0301').takeIf { it > 0 }?.let { index -> word.take(index).count { it in vowels } } ?: 0
            var target = exceptions[bare] ?: bare
            if (bare !in exceptions && bare !in ogoBlacklist && (bare.endsWith("ого") || bare.endsWith("его"))) {
                target = bare.dropLast(2) + "во"
            }
            clusters.forEach { (from, to) -> target = target.replace(from, to) }
            var n = 0
            buildString { for (c in target) { append(c); if (c in vowels && ++n == ordinal) append('\u0301') } }
        }
    }

    /** eSpeak does not return source punctuation. Preserve it outside the native call. */
    fun phonemize(text: String, native: (String) -> String): String {
        val input = marked(text)
        val output = StringBuilder()
        var start = 0
        fun appendSpeech(end: Int) {
            val phrase = input.substring(start, end).trim()
            if (phrase.isNotEmpty()) {
                val ipa = normalizeIpa(native(phrase))
                if (ipa.isNotBlank()) { if (output.isNotEmpty()) output.append(' '); output.append(ipa) }
            }
        }
        for (mark in marks.findAll(input)) {
            appendSpeech(mark.range.first)
            output.append(mark.value)
            start = mark.range.last + 1
        }
        appendSpeech(input.length)
        return output.toString().trim()
    }

    fun normalizeIpa(source: String): String {
        var ipa = Regex("\\([a-z-]+\\)").replace(source, "")
        for ((from, to) in listOf("a^ɪ" to "I", "a^ʊ" to "W", "d^z" to "ʣ", "d^ʒ" to "ʤ",
            "e^ɪ" to "A", "o^ʊ" to "O", "ə^ʊ" to "Q", "s^s" to "S", "t^s" to "ʦ", "t^ʃ" to "ʧ", "ɔ^ɪ" to "Y")) ipa = ipa.replace(from, to)
        ipa = ipa.replace("^", "").replace("-", "").replace("u\"", "u").replace('ɭ', 'l')
            .replace('ɵ', 'o').replace('ʑ', 'ʒ').replace('ʐ', 'ʒ').replace("ʧʲ", "ʧ")
        return ipa.trim().split(Regex("\\s+")).joinToString(" ") { reduceToken(it) }.replace(Regex("ɕ(?!ː)"), "ɕː")
    }

    private fun reduceToken(token: String): String {
        val chars = token.toCharArray()
        val positions = chars.indices.filter { chars[it] in "aɑoeiuyʌəɪɐɛɨ" }
        if (positions.isEmpty()) return token
        val primary = token.indexOf('ˈ')
        val stressed = chars.indices.filter { i -> chars[i] in "ˈˌ" && !(chars[i] == 'ˌ' && primary >= 0 && i > primary) }
            .mapNotNull { i -> positions.firstOrNull { it > i } }.toSet()
        val ordinals = positions.indices.filter { positions[it] in stressed }
        for ((ordinal, index) in positions.withIndex()) {
            if (index in stressed || chars[index] !in "aɑoʌə") continue
            chars[index] = if (index > 0 && chars[index - 1] in "ʲj") 'ɪ'
                else if (index == 0 || ordinals.firstOrNull { it > ordinal } == ordinal + 1) 'ɐ' else 'ə'
        }
        return chars.filterIndexed { i, c -> !(c == 'ˌ' && primary >= 0 && i > primary) }.joinToString("")
    }

    /** Split only at word boundaries, below the model's 512-token context. */
    fun chunks(ipa: String, limit: Int = 500): List<String> {
        require(limit in 1..510)
        val output = mutableListOf<String>()
        var remaining = ipa.trim()
        while (remaining.length > limit) {
            val boundary = remaining.lastIndexOf(' ', limit)
            require(boundary > 0) { "Kokoro: слово превышает лимит фонем" }
            output += remaining.substring(0, boundary).trimEnd()
            remaining = remaining.substring(boundary + 1).trimStart()
        }
        if (remaining.isNotEmpty()) output += remaining
        return output
    }
}
