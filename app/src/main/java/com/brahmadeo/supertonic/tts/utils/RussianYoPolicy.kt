package com.brahmadeo.supertonic.tts.utils

/** Applies to dictionary/accentor output. Existing ё and user stress are retained. */
object RussianYoPolicy {
    private val marks = setOf('+', '\u0301')
    private val words = Regex("[+А-Яа-яЁё\\u0301]+")
    private val wallForms = setOf("стена", "стены", "стене", "стену", "стеной", "стеною", "стен", "стенам", "стенами", "стенах")
    fun apply(source: String, prepared: String, restoreYo: Boolean): String {
        val original = source.filter { it !in marks }
        val plain = prepared.filter { it !in marks }
        var result = prepared
        if (!restoreYo && original.replace('ё','е').replace('Ё','Е') == plain.replace('ё','е').replace('Ё','Е')) {
            var index = 0
            result = prepared.map { c ->
                if(c in marks) c else {
                    val before = original[index++]
                    when { c == 'ё' && before != 'ё' -> 'е'; c == 'Ё' && before != 'Ё' -> 'Е'; else -> c }
                }
            }.joinToString("")
        }
        return words.replace(result) { match ->
            val word = match.value
            if(word.filter { it !in marks }.lowercase().replace('ё','е') in wallForms)
                word.replace('ё','е').replace('Ё','Е') else word
        }
    }
}
