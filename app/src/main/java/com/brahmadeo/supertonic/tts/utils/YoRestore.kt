package com.brahmadeo.supertonic.tts.utils

/** ё the LLM left as е, from a verified table of words whose ё is certain (Silero's safe ё SACC):
 * "черный" -> "чёрный", "ребенок" -> "ребёнок". Ambiguous pairs (все/всё, узнает/узнаёт) are never touched, nor is
 * any letter other than е->ё. ё carries the stress, so a mark on that word is dropped. Books typed without ё rely on
 * this when the LLM misses one. */
object YoRestore {
    private const val ACUTE = '\u0301'
    private val word = Regex("[А-Яа-яЁё\u0301]+")

    fun apply(text: String, ambiguous: Set<String>, lookup: (String) -> String?): String = word.replace(text) { m ->
        val w = m.value
        if ('ё' in w || 'Ё' in w) return@replace w
        val bare = w.replace(ACUTE.toString(), "")
        val lower = bare.lowercase()
        if ('е' !in lower || lower in ambiguous) return@replace w
        val form = lookup(lower)?.replace(ACUTE.toString(), "")?.replace("+", "")?.lowercase() ?: return@replace w
        if ('ё' !in form || form.length != lower.length || form.replace('ё', 'е') != lower) return@replace w
        buildString { for (i in bare.indices) append(if (form[i] == 'ё') (if (bare[i].isUpperCase()) 'Ё' else 'ё') else bare[i]) }
    }
}
