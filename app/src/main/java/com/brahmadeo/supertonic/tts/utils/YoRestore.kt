package com.brahmadeo.supertonic.tts.utils

/** ё the LLM left as е, from a verified table of words whose ё is certain (Silero's safe ё SACC):
 * "черный" -> "чёрный", "ребенок" -> "ребёнок". Ambiguous pairs (все/всё, узнает/узнаёт) are never touched, nor is
 * any letter other than е->ё. ё carries the stress, so a mark on that word is dropped. Books typed without ё rely on
 * this when the LLM misses one. */
object YoRestore {
    private const val ACUTE = '\u0301'
    // '+' inside the word as in StressCheck.explicitOccurrences, so occurrence numbers line up.
    private val word = Regex("[+А-Яа-яЁё\u0301]+")

    /** [explicit]: occurrences the book or the user lexicon marked (bare lowercase -> occurrence numbers): kept as
     * written («че́рный» of the book stays). The table's ё is certain and stressed, so an LLM mark elsewhere goes. */
    fun apply(text: String, ambiguous: Set<String>, explicit: Map<String, Set<Int>> = emptyMap(), lookup: (String) -> String?): String {
        val occurrences = HashMap<String, Int>()
        return word.replace(text) { m ->
            val w = m.value
            val bare = w.replace(ACUTE.toString(), "").replace("+", "")
            val lower = bare.lowercase()
            val n = occurrences[lower] ?: 0
            occurrences[lower] = n + 1
            if ('+' in w || 'ё' in w || 'Ё' in w || explicit[lower]?.contains(n) == true) return@replace w
            if ('е' !in lower || lower in ambiguous) return@replace w
            val form = lookup(lower)?.replace(ACUTE.toString(), "")?.replace("+", "")?.lowercase() ?: return@replace w
            if ('ё' !in form || form.length != lower.length || form.replace('ё', 'е') != lower) return@replace w
            buildString { for (i in bare.indices) append(if (form[i] == 'ё') (if (bare[i].isUpperCase()) 'Ё' else 'ё') else bare[i]) }
        }
    }
}
