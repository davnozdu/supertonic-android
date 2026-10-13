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
    // '+' inside the word as in StressCheck.explicitOccurrences, so occurrence numbers line up.
    private val word = Regex("[+А-Яа-яЁё́]+")
    /** Name forms that open a sentence equally well as common words ("Семена взошли"), with the name's family. */
    private val ambiguousAtSentenceStart = mapOf("семена" to "семен")
    /** Name families met inside sentences in this reading (RAM): then a sentence-initial "Семена" is the name too. */
    private val seen = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private fun ambiguousHere(text: String, index: Int, key: String): Boolean {
        val family = ambiguousAtSentenceStart[key] ?: return false
        return sentenceStart(text, index) && family !in seen
    }
    private fun remember(key: String) { if (seen.size > 2000) seen.clear(); seen += key; seen += key.dropLast(1) }
    /** A new reading (another client, new app text, changed settings): names of the previous book no longer count. */
    fun forget() = seen.clear()

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
    private fun sentenceStart(text: String, index: Int) = SentenceStart.at(text, index)

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

    /** The dictionary form limited to what the settings allow: without [yo] the ё is read as stressed е (or plain е
     * without [stress]); without [stress] no mark is added. */
    private fun allowed(form: String, stress: Boolean, yo: Boolean): String {
        var f = form
        if (!yo && 'ё' in f) f = if (stress && ACUTE !in f) f.replaceFirst("ё", "е$ACUTE").replace('ё', 'е') else f.replace('ё', 'е')
        if (!stress) f = f.replace(ACUTE.toString(), "")
        return f
    }

    /** Capitalised names (and names in capitals, «ФЕДОР») get the dictionary's ё and its stress when the word has no
     * mark yet. A mark the LLM placed stays (the stress check compares it with this dictionary). ё replaces a mark
     * on the same vowel («Семе́н» -> «Семён»), not one on another vowel: «Федо́ра Егоровна» is another name than the
     * dictionary's «Фёдора». [explicit]: the occurrences the book or the user lexicon marked (bare lowercase word ->
     * occurrence numbers, [com.brahmadeo.supertonic.tts.llm.StressCheck.explicitOccurrences]) are never changed.
     * [stress] and [yo] are the user's switches: each adds only its own kind of mark. */
    fun restore(ctx: Context?, text: String, stress: Boolean = true, yo: Boolean = true,
                explicit: Map<String, Set<Int>> = emptyMap()): String {
        if (!stress && !yo) return text
        if (forms == null && ctx != null) init(ctx)
        val map = forms ?: return text
        if (map.isEmpty()) return text
        val occurrences = HashMap<String, Int>()
        val t = compounds(text, map, marked = false) { allowed(it, stress, yo) }
        return word.replace(t) { m ->
            val w = m.value
            val plain = w.replace(ACUTE.toString(), "").replace("+", "").lowercase()
            val n = occurrences[plain] ?: 0
            occurrences[plain] = n + 1
            if (!(capitalised(w) || capitals(w)) || '+' in w || explicit[plain]?.contains(n) == true) return@replace w
            val f = map[key(w)]?.let { allowed(it, stress, yo) } ?: return@replace w
            if (ambiguousHere(t, m.range.first, key(w))) return@replace w
            if (!sentenceStart(t, m.range.first)) remember(key(w))
            val hasYo = 'ё' in f
            when {
                ACUTE !in w && 'ё' !in w && 'Ё' !in w -> respell(w, f)
                hasYo && 'ё' !in w.lowercase() && stressed(w) == yoVowel(f) -> respell(w, f)
                else -> w
            }
        }
    }
    /** Unmarked hyphenated names the dictionary has as a whole (Алма-Ата́, Али-Баба́, Абу-Да́би): one mark for the name,
     * not a guess for each part. Marked parts and the book's own spelling are left to the word pass. */
    private val compound = Regex("(?<![+А-Яа-яЁё́-])\\+?[А-ЯЁ][+А-Яа-яЁё́]*(?:-[+А-Яа-яЁё́]+)+(?![+А-Яа-яЁё́-])")
    /** [marked]: Silero's opinion, whose marks are replaced; otherwise a marked part keeps the name as written. */
    private fun compounds(text: String, map: Map<String, String>, marked: Boolean, form: (String) -> String): String =
        if ('-' !in text) text else compound.replace(text) { m ->
            if (!marked && m.value.any { it == '+' || it == ACUTE }) m.value
            else map[key(m.value)]?.let { respell(m.value, form(it)) } ?: m.value
        }
    /** A whole word in capitals (a heading, «ФЕДОР»), at least three letters: abbreviations are shorter or unknown. */
    private fun capitals(w: String) = w.length >= 3 && w.all { it.isUpperCase() || it == ACUTE }
    private fun stressed(w: String): Int? = w.indexOf(ACUTE).takeIf { it > 0 }?.let { i -> w.substring(0, i).count { it in VOWELS } }
    private fun yoVowel(f: String): Int = f.substring(0, f.indexOf('ё') + 1).count { it in VOWELS }

    /** The offline opinion for the stress check: every capitalised name the dictionary knows takes its stress, so
     * the LLM's name marks are compared with Wiktionary rather than with Silero's guess for an unknown word.
     * [text] is Silero's output: its marks are '+' («Волк+ов»), replaced here (before 5.0.4-beta27 a '+' word was
     * skipped, so the overlay almost never applied and RUAccent's «волко́в» agreed with Silero against Во́лков). */
    fun overlay(ctx: Context?, text: String): String {
        if (forms == null && ctx != null) init(ctx)
        val map = forms ?: return text
        val t = compounds(text, map, marked = true) { it }
        return word.replace(t) { m ->
            val w = m.value
            if (!capitalised(w.replace("+", ""))) return@replace w
            val f = map[key(w)] ?: return@replace w
            if (ACUTE !in f || ambiguousHere(t, m.range.first, key(w))) w else respell(w, f)
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
