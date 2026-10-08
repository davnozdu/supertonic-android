package com.brahmadeo.supertonic.tts.utils

import android.content.Context

/** Stress and ё of Russian proper names (given names, surnames, places) in all their forms, from Wiktionary
 * (assets/names_ru.tsv, built by tools/names/build_names.py, CC BY-SA 4.0). Books are often typed without ё
 * ("Семен", "Петр", "Федор"), and the general accent dictionary gets names wrong (Фе́дор, Але́на, Хруще́в);
 * this one gives Семён, Пётр, Фёдор, Алёна, Хрущёв, Петра́, Твери́.
 * Only capitalised words are touched. "Семена" at the start of a sentence may be "seeds" and is left alone. */
object RussianNames {
    private const val ACUTE = '́'
    private const val VOWELS = "аеёиоуыэюяАЕЁИОУЫЭЮЯ"
    @Volatile private var forms: Map<String, String>? = null
    private val word = Regex("[А-Яа-яЁё́]+")
    /** Name forms that open a sentence equally well as common words ("Семена взошли"), with the name's family. */
    private val ambiguousAtSentenceStart = mapOf("семена" to "семен")
    /** Name families met inside sentences in this reading (RAM): then a sentence-initial "Семена" is the name too. */
    private val seen = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private fun ambiguousHere(text: String, index: Int, key: String): Boolean {
        val family = ambiguousAtSentenceStart[key] ?: return false
        return sentenceStart(text, index) && family !in seen
    }
    private fun remember(key: String) { if (seen.size > 2000) seen.clear(); seen += key; seen += key.dropLast(1) }

    /** For tests: the dictionary from the asset text. */
    fun load(lines: Sequence<String>): Map<String, String> = lines.filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    @Synchronized fun init(ctx: Context) {
        if (forms != null) return
        forms = runCatching { ctx.assets.open("names_ru.tsv").bufferedReader().useLines { load(it) } }.getOrDefault(emptyMap())
    }
    internal fun initForTests(map: Map<String, String>) { forms = map; seen.clear() }

    private fun key(w: String) = w.replace(ACUTE.toString(), "").replace("+", "").lowercase().replace('ё', 'е')
    private fun capitalised(w: String) = w.first().isUpperCase() && w.drop(1).any { it.isLowerCase() }
    private fun sentenceStart(text: String, index: Int): Boolean {
        var i = index - 1
        while (i >= 0 && (text[i].isWhitespace() || text[i] in "«»\"„“”'()[]—–-")) { if (text[i] == '\n') return true; i-- }
        return i < 0 || text[i] in ".!?…:;"
    }

    /** Dictionary form of a capitalised word, lowercase with ё and an acute mark (null when unknown). */
    fun form(word: String): String? = forms?.get(key(word))
    /** Ordinal of the stressed vowel of the dictionary form (ё counts as stressed). */
    fun ordinal(word: String): Int? {
        val f = form(word) ?: return null
        val mark = f.indexOf(ACUTE)
        return when {
            mark > 0 -> f.substring(0, mark).count { it in VOWELS }
            'ё' in f -> f.substring(0, f.indexOf('ё') + 1).count { it in VOWELS }
            else -> null
        }
    }

    /** The word spelled as the dictionary has it (ё, stress), keeping the original capitals. */
    private fun respell(original: String, form: String): String {
        val letters = form.replace(ACUTE.toString(), "")
        val bare = original.replace(ACUTE.toString(), "").replace("+", "")
        if (letters.length != bare.length) return original
        val out = StringBuilder()
        for (i in letters.indices) out.append(if (bare[i].isUpperCase()) letters[i].uppercaseChar() else letters[i])
        val mark = form.indexOf(ACUTE)
        return if (mark > 0) out.insert(mark, ACUTE).toString() else out.toString()
    }

    /** Capitalised names get the dictionary's ё always and its stress when the word has no mark yet. A mark the
     * LLM placed stays (the stress check compares it with this dictionary); ё makes any other mark redundant. */
    fun restore(ctx: Context?, text: String): String {
        if (forms == null && ctx != null) init(ctx)
        val map = forms ?: return text
        if (map.isEmpty()) return text
        return word.replace(text) { m ->
            val w = m.value
            if (!capitalised(w) || '+' in w) return@replace w
            val f = map[key(w)] ?: return@replace w
            if (ambiguousHere(text, m.range.first, key(w))) return@replace w
            if (!sentenceStart(text, m.range.first)) remember(key(w))
            val hasYo = 'ё' in f
            when {
                ACUTE !in w && 'ё' !in w && 'Ё' !in w -> respell(w, f)
                // ё carries the stress itself, so the LLM's mark (possibly on another vowel) is replaced.
                hasYo && 'ё' !in w.lowercase() -> respell(w, f)
                else -> w
            }
        }
    }

    /** The offline opinion for the stress check: every capitalised name the dictionary knows takes its stress, so
     * the LLM's name marks are compared with Wiktionary rather than with Silero's guess for an unknown word. */
    fun overlay(ctx: Context?, text: String): String {
        if (forms == null && ctx != null) init(ctx)
        val map = forms ?: return text
        return word.replace(text) { m ->
            val w = m.value
            if (!capitalised(w) || '+' in w) return@replace w
            val f = map[key(w)] ?: return@replace w
            if (ACUTE !in f || ambiguousHere(text, m.range.first, key(w))) w else respell(w, f)
        }
    }

    /** Names of the batch spelled as the dictionary has them, for the LLM's "names" hint ("Семён", "Петра́"). */
    fun hint(ctx: Context?, texts: List<String>, limit: Int = 40): List<String> {
        if (forms == null && ctx != null) init(ctx)
        val map = forms ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (t in texts) for (m in word.findAll(t)) {
            val w = m.value
            if (!capitalised(w) || sentenceStart(t, m.range.first)) continue
            map[key(w)]?.let { out += respell(w, it) }
            if (out.size >= limit) return out.toList()
        }
        return out.toList()
    }
}
