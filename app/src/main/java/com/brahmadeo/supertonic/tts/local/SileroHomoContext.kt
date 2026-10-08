package com.brahmadeo.supertonic.tts.local

import org.json.JSONArray

/** Silero Stress 1.5 homograph front end, ported from its homosolver: the cleaned ±150-character context around a
 * homograph ("... [HOMO] word [/HOMO] ...") and the phrase rules (1477 words, 37 445 phrases) that decide a
 * homograph before the BERT classifier. Phrases are checked only for homographs actually found in a text, each word's
 * pattern is compiled on first use and kept in a small LRU, so the cost on the phone is a regex search over ≤300 chars.
 * [rules] returns a word's rule line (JSON array) — the table stays as plain strings, parsed per word on use. */
class SileroHomoContext(private val rules: (String) -> String?) {
    private val removeExtra = Regex("[^a-zA-Zа-яА-ЯёЁ0-9\\s.!?,\\-]")
    private val spaces = Regex("\\s+")
    private val doubleDash = Regex("-{2,}")
    private val repeatPunct = Regex("([.!?])\\1+")
    private val repeatComma = Regex(",{2,}")
    private val spaceBeforePunct = Regex("\\s+([.,!?])")
    private val punctAddSpace = Regex("([.,!?])(?=\\S)")
    private val compiled = object : LinkedHashMap<String, Pair<Regex, List<String>>?>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Regex, List<String>>?>?) = size > 128
    }

    companion object {
        /** The asset table: "word<TAB>json" lines -> word to rule line. */
        fun load(lines: Sequence<String>): Map<String, String> =
            lines.mapNotNull { l -> l.indexOf('\t').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
    }

    /** Python HomoSolver._clean_text. */
    fun clean(text: String, isStart: Boolean): String {
        if (text.isEmpty()) return ""
        var t = removeExtra.replace(text, "")
        t = spaces.replace(t, " ")
        t = doubleDash.replace(t, " - ")
        t = repeatPunct.replace(t, "$1")
        t = repeatComma.replace(t, ",")
        t = spaceBeforePunct.replace(t, "$1")
        t = repeatPunct.replace(t, "$1")
        t = repeatComma.replace(t, ",")
        t = punctAddSpace.replace(t, "$1 ")
        t = spaces.replace(t, " ").trim()
        if (isStart) {
            t = t.trimStart(' ', '.', ',', '!', '?', '-')
            if (t.isNotEmpty()) t = t[0].uppercase() + t.substring(1).lowercase()
        } else {
            if (t.isNotEmpty()) t = t[0] + t.substring(1).lowercase()
            if (t.isNotEmpty() && t.last() !in ".!?") t += "."
        }
        return t
    }

    /** "left [HOMO] word [/HOMO] right" as Silero builds it for the word at [start, end) of [text]. */
    fun marked(text: String, start: Int, end: Int, wordLower: String): String {
        val left = clean(text.substring(maxOf(0, start - 600), start), isStart = true).takeLast(150)
        val right = clean(text.substring(end, minOf(text.length, end + 600)), isStart = false).take(150)
        return "$left [HOMO] $wordLower [/HOMO] $right".trim()
    }

    fun hasPhrases(wordLower: String) = rules(wordLower) != null

    /** The variant a phrase rule decides ("б+елки"), or null when no phrase matches. Leftmost match wins, then the
     * variant listed first, exactly like Python's alternation search. */
    fun phrase(wordLower: String, marked: String): String? {
        val entry = synchronized(compiled) {
            if (compiled.containsKey(wordLower)) compiled[wordLower] else build(wordLower).also { compiled[wordLower] = it }
        } ?: return null
        val match = entry.first.find(marked) ?: return null
        val group = (1..entry.second.size).firstOrNull { match.groups[it] != null } ?: return null
        return decapitalize(entry.second[group - 1])
    }

    private fun build(wordLower: String): Pair<Regex, List<String>>? {
        val variants = JSONArray(rules(wordLower) ?: return null)
        val names = mutableListOf<String>()
        val groups = mutableListOf<String>()
        for (i in 0 until variants.length()) {
            val v = variants.getJSONArray(i)
            val list = v.getJSONArray(1)
            val alts = (0 until list.length()).map { Regex.escape(list.getString(it)) }
            names += v.getString(0)
            groups += "((?<![а-яА-ЯёЁ\\-])(?:${alts.joinToString("|")})(?![а-яА-ЯёЁ\\-]))"
        }
        return Regex(groups.joinToString("|"), setOf(RegexOption.IGNORE_CASE)) to names
    }

    /** "бЕлки" -> "б+елки" (Python decapitalize_stress). */
    private fun decapitalize(name: String): String {
        val i = name.indexOfFirst { it.isUpperCase() }
        return if (i < 0) name else name.substring(0, i) + "+" + name.substring(i).lowercase()
    }
}
