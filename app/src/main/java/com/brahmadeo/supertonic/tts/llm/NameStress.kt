package com.brahmadeo.supertonic.tts.llm

/** Stress of proper names, learned from validated LLM output while a book is read (RAM only).
 * A name is a capitalized word that does not start a sentence ("князь Мы́шкин", "у Рого́жина").
 * The LLM receives the names it already stressed as a hint ([hint]), so one character keeps one
 * pronunciation across batches; a known name the LLM left without a mark gets the stress it used
 * most often ([fill]). Marks the LLM did place are never changed. */
object NameStress {
    private const val VOWELS = "аеёиоуыэюяАЕЁИОУЫЭЮЯ"
    private const val ACUTE = '́'
    private const val MAX_NAMES = 2000
    private val word = Regex("[А-Яа-яЁё́]+")
    /** Bare lowercase form -> stressed vowel ordinal (1-based) -> occurrences. */
    private val names = object : LinkedHashMap<String, MutableMap<Int, Int>>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<Int, Int>>?) = size > MAX_NAMES
    }

    private fun bare(w: String) = w.replace(ACUTE.toString(), "")
    /** One key for «Семенов» and «Семёнов». */
    private fun key(w: String) = bare(w).lowercase().replace('ё', 'е')
    private fun vowelCount(w: String) = w.count { it in VOWELS }
    private fun capitalized(w: String) = w.first().isUpperCase() && w.drop(1).any { it.isLowerCase() }
    private fun sentenceStart(text: String, index: Int) = com.brahmadeo.supertonic.tts.utils.SentenceStart.at(text, index)
    /** Ordinal of the vowel carrying the acute mark, or null when there is none. */
    private fun stressOrdinal(w: String): Int? {
        val mark = w.indexOf(ACUTE)
        if (mark <= 0 || w.indexOf(ACUTE, mark + 1) >= 0 || w[mark - 1] !in VOWELS) return null
        return w.substring(0, mark).count { it in VOWELS }.takeIf { it > 0 }
    }
    private fun best(key: String): Int? = names[key]?.maxByOrNull { it.value }?.key
    private fun withStress(w: String, ordinal: Int): String {
        var n = 0
        val out = StringBuilder()
        for (c in w) { out.append(c); if (c in VOWELS && ++n == ordinal) out.append(ACUTE) }
        return out.toString()
    }

    /** Keys of the words of [text] that carry a mark: what an LLM stressed itself. */
    fun marked(text: String): Set<String> = word.findAll(text).map { it.value }.filter { ACUTE in it }.map { key(it) }.toSet()

    /** Learns the names of [text]; with [only], just those keys (the words the LLM marked itself). */
    @Synchronized fun learn(text: String, only: Set<String>? = null) {
        for (m in word.findAll(text)) {
            val w = m.value
            if (!capitalized(w) || sentenceStart(text, m.range.first) || vowelCount(w) < 2) continue
            if (only != null && key(w) !in only) continue
            val ordinal = stressOrdinal(w) ?: continue
            val counts = names.getOrPut(key(w)) { HashMap() }
            counts[ordinal] = (counts[ordinal] ?: 0) + 1
        }
    }

    /** Known names occurring in [texts], stressed the way this book has used them so far. */
    @Synchronized fun hint(texts: List<String>, limit: Int = 60): List<String> {
        val out = LinkedHashSet<String>()
        for (text in texts) for (m in word.findAll(text)) {
            val w = bare(m.value)
            if (!capitalized(w) || vowelCount(w) < 2) continue
            val ordinal = best(key(w)) ?: continue
            out += withStress(w, ordinal)
            if (out.size >= limit) return out.toList()
        }
        return out.toList()
    }

    /** Adds the learned stress to known names without a mark (or ё); everything else is returned as is. */
    @Synchronized fun fill(text: String): String = word.replace(text) { m ->
        val w = m.value
        if (ACUTE in w || 'ё' in w || 'Ё' in w || !capitalized(w) || vowelCount(w) < 2) w
        else best(key(w))?.let { withStress(w, it) } ?: w
    }

    @Synchronized fun clear() = names.clear()
}
