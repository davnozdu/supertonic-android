package com.brahmadeo.supertonic.tts.llm

/** Stress marks, the chain the user chose after comparing them (5.0.4-beta8): the dictionaries mark, the LLM arbitrates.
 * RUAccent's dictionary (3.2 M Zaliznyak forms; its 19.7 k homographs left out) and Silero Stress (context BERT for its
 * homographs, accentor for unknown words) mark every word independently. Where they agree the mark is final; where they
 * disagree, and for every RUAccent homograph, the LLM judge chooses between the two variants in the sentence; a word
 * neither dictionary knows keeps the LLM's own mark. On the probe sets (DeepSeek v4.1 Flash judge): hard 70/71 and
 * «Идиот» ch. 1 40/41, against 67/71 and 39/41 for the previous LLM-first chain; RUAccent's 343 MB homograph model
 * instead of the dictionary gave 65/71, 39/41 (both dictionaries agreeing on a wrong homograph is not asked).
 * Without a judge a disagreement takes RUAccent's mark and a homograph Silero's (61/71, 37/41; Silero alone 61/71, 36/41). */
object StressCheck {
    /** [sentence] has the target word in ⟨angle brackets⟩; [context] is the sentence before it. */
    data class Item(val sentence: String, val word: String, val options: List<String>, val context: String = "")
    /** A word for the judge: [first] is RUAccent's variant (or Silero's for a homograph), [second] the other one.
     * [llm]: the stressed-vowel ordinal the LLM itself gave the word, if any. */
    data class Dispute(val fragment: Int, val range: IntRange, val sentence: String, val first: String, val second: String,
                       val context: String = "", val homograph: Boolean = false, val llm: Int? = null)
    /** A decided word: [value] replaces [range] of [fragment]. */
    data class Mark(val fragment: Int, val range: IntRange, val value: String)
    /** [fragments] as given; [marks] (agreed or single-source) and [disputes] are applied together by [resolve]. */
    class Plan(val fragments: List<String>, val marks: List<Mark>, val disputes: List<Dispute>,
               val agreed: Int, val single: Int, val llmOnly: Int)

    private const val ACUTE = '́'
    private const val VOWELS = "аеёиоуыэюяАЕЁИОУЫЭЮЯ"
    /** Judge items per batch; the rest take the no-judge rule. ~2400 chars of a book gave 5-20 disputes. */
    const val MAX_JUDGED = 96
    private val word = Regex("[+А-Яа-яЁё́]+")
    private val plusVowel = Regex("\\+([аеёиоуыэюяАЕЁИОУЫЭЮЯ])")

    private var decisions = mutableListOf<String>()
    /** Diagnostics: decisions since the previous call ("first / second -> chosen | sentence"). */
    @Synchronized fun takeDecisions(): List<String> = decisions.toList().also { decisions.clear() }
    @Volatile var lastSecondChosen = 0; private set
    @Volatile var lastTieBreaks = 0; private set

    /** 1-based stressed vowel of [w] ('+' before or U+0301 after the vowel), null without exactly one mark. */
    fun ordinal(w: String): Int? {
        val acute = plusVowel.replace(w) { it.groupValues[1] + ACUTE }
        val mark = acute.indexOf(ACUTE)
        if (mark <= 0 || acute.indexOf(ACUTE, mark + 1) >= 0 || acute[mark - 1] !in VOWELS) return null
        return acute.substring(0, mark).count { it in VOWELS }
    }
    private fun bare(w: String) = w.replace(ACUTE.toString(), "").replace("+", "")
    fun withStress(w: String, ordinal: Int): String {
        var n = 0
        val out = StringBuilder()
        for (c in bare(w)) { out.append(c); if (c in VOWELS && ++n == ordinal) out.append(ACUTE) }
        return out.toString()
    }
    private fun key(w: String) = bare(w).lowercase().replace('ё', 'е')
    private fun sentenceStart(text: String, index: Int): Int {
        var from = index
        while (from > 0 && text[from - 1] !in ".!?…\n" && index - from < 220) from--
        return from
    }
    /** The sentence of [range] with the word marked ⟨so⟩ (one sentence may hold the same word twice). */
    private fun sentence(text: String, range: IntRange): String {
        val from = sentenceStart(text, range.first)
        var to = range.last + 1
        while (to < text.length && text[to - 1] !in ".!?…\n" && to - range.last < 220) to++
        return (text.substring(from, range.first) + "⟨" + text.substring(range.first, range.last + 1) + "⟩" + text.substring(range.last + 1, to))
            .replace(ACUTE.toString(), "").trim()
    }
    private fun previousSentence(text: String, range: IntRange): String {
        val start = sentenceStart(text, range.first)
        if (start == 0) return ""
        return text.substring(sentenceStart(text, start - 1), start).replace(ACUTE.toString(), "").trim().takeLast(220)
    }

    /** Text without acute marks, for Silero's independent opinion (ё and explicit '+' of the source stay). */
    fun unmarked(text: String) = text.replace(ACUTE.toString(), "")

    /** Bare lowercase words the book itself (or the user lexicon) stressed with '+' or U+0301: never changed. */
    fun explicitWords(source: String): Set<String> =
        word.findAll(source).map { it.value }.filter { w -> w.any { it == '+' || it == ACUTE } }.map { bare(it).lowercase() }.toSet()

    /** [fragments]: the LLM's text with its own marks, or the plain text without an LLM. [silero]: Silero Stress on
     * [unmarked] of the same fragment. [dictionary]: RUAccent's ordinal of a bare lowercase word (null = unknown).
     * [homograph]: the variant ordinals of a RUAccent homograph. [keep]: words never touched (fragment, bare lowercase).
     * [llmAsDictionary]: the dictionary is not downloaded yet; the LLM's own mark stands in for it. */
    fun plan(fragments: List<String>, silero: List<String>, dictionary: (String) -> Int?, homograph: (String) -> List<Int>?,
             keep: (Int, String) -> Boolean = { _, _ -> false }, llmAsDictionary: Boolean = false): Plan {
        val marks = mutableListOf<Mark>()
        val disputes = mutableListOf<Dispute>()
        var agreed = 0; var single = 0; var llmOnly = 0
        for (f in fragments.indices) {
            val a = word.findAll(fragments[f]).toList()
            val b = word.findAll(silero[f]).toList()
            val aligned = a.size == b.size && a.indices.all { key(a[it].value) == key(b[it].value) }
            for (i in a.indices) {
                val w = a[i].value
                if ('+' in w || 'ё' in w || 'Ё' in w || w.count { it in VOWELS } < 2 || keep(f, bare(w).lowercase())) continue
                val k = bare(w).lowercase()
                val variants = homograph(k)?.distinct()?.takeIf { it.size >= 2 }
                val llm = ordinal(w)
                val r = if (variants != null) null else dictionary(k) ?: if (llmAsDictionary) llm else null
                val s = if (aligned) ordinal(b[i].value) else null
                fun dispute(x: Int, y: Int, homo: Boolean) { disputes += Dispute(f, a[i].range, sentence(fragments[f], a[i].range),
                    withStress(w, x), withStress(w, y), previousSentence(fragments[f], a[i].range), homo, llm) }
                when {
                    r != null && s != null && r == s -> { marks += Mark(f, a[i].range, withStress(w, r)); agreed++ }
                    r != null && s != null -> dispute(r, s, false)
                    variants?.size == 2 -> {
                        val m = r ?: s
                        if (m == null) dispute(variants[0], variants[1], true)
                        else if (m in variants) dispute(m, variants.first { it != m }, true)
                        else { marks += Mark(f, a[i].range, withStress(w, m)); single++ }
                    }
                    r != null || s != null -> { marks += Mark(f, a[i].range, withStress(w, (r ?: s)!!)); single++ }
                    llm != null -> llmOnly++
                }
            }
        }
        return Plan(fragments, marks, disputes, agreed, single, llmOnly)
    }

    /** Every dispute is asked twice, [first] option first and then [second] first. In a single ask the model followed
     * the option position about as often as the meaning; the two orders go in two requests (duplicates in one request
     * came back merged). */
    fun items(disputes: List<Dispute>, secondFirst: Boolean): List<Item> = disputes.map {
        Item(it.sentence, bare(it.first), if (secondFirst) listOf(it.second, it.first) else listOf(it.first, it.second), it.context)
    }

    /** The no-judge rule: the LLM's own mark when it is one of the two, otherwise [first] (RUAccent's, or Silero's for a
     * homograph). [tie] may decide first (a name dictionary); null = no opinion. */
    private fun fallback(d: Dispute, tie: (Dispute) -> Int?): String {
        val a = ordinal(d.first); val b = ordinal(d.second)
        tie(d)?.let { if (it == a) return d.first; if (it == b) return d.second }
        return if (d.llm != null && d.llm == b) d.second else d.first
    }

    /** Applies the judge's [choices]: answers to the first-first request followed by the second-first one (null = none);
     * null [choices] = no judge at all. A mark follows the judge only when both orders pick the same variant. */
    fun resolve(plan: Plan, choices: List<Int?>?, tie: (Dispute) -> Int? = { null }): List<String> {
        val out = plan.fragments.map { StringBuilder(it) }
        val log = mutableListOf<String>()
        val n = plan.disputes.size
        var second = 0; var ties = 0
        val all = plan.marks.toMutableList()
        for ((i, d) in plan.disputes.withIndex()) {
            val p = choices?.getOrNull(i); val q = choices?.getOrNull(i + n)
            val chosen = when {
                p == 0 && q == 1 -> d.first
                p == 1 && q == 0 -> d.second.also { second++ }
                else -> fallback(d, tie).also { if (choices != null) ties++ }
            }
            log += "${if (d.homograph) "H " else ""}${d.first} / ${d.second} -> $chosen [${p ?: "-"}${q ?: "-"}] | ${d.sentence.take(120)}"
            all += Mark(d.fragment, d.range, chosen)
        }
        // Right to left keeps the remaining ranges of a fragment valid (a new mark makes its word one char longer).
        for (m in all.sortedByDescending { it.range.first }) out[m.fragment].replace(m.range.first, m.range.last + 1, m.value)
        synchronized(this) { decisions += log.reversed(); if (decisions.size > 500) decisions = decisions.takeLast(500).toMutableList() }
        lastSecondChosen = second; lastTieBreaks = ties
        return out.map { it.toString() }
    }
}
