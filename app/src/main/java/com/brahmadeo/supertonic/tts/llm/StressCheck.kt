package com.brahmadeo.supertonic.tts.llm

/** Cross-check of the cloud LLM's stress marks. The offline Silero Stress (context BERT for homographs,
 * dictionary + accentor for the rest) marks the same text independently; words where the two disagree go
 * back to the LLM with their sentence and both options (LlmProviders.verifyStress), and its choice is final.
 * On the first chapter of «Идиот» this is where the LLM's slips were (оттепе́ль, на́хальною, субъекте́,
 * нача́л for the verb); its strengths, names and homographs, it keeps when asked again. Any failure keeps
 * the LLM's original marks. */
object StressCheck {
    /** [sentence] has the target word in ⟨angle brackets⟩; [context] is the sentence before it. */
    data class Item(val sentence: String, val word: String, val options: List<String>, val context: String = "")
    /** One disagreement: [fragment] index, word [range] in that fragment, the two variants. [homograph]: the LLM and
     * Silero agreed, but the word is a known homograph and is asked anyway (no dictionary tie-break for those). */
    data class Dispute(val fragment: Int, val range: IntRange, val sentence: String, val llm: String, val offline: String,
                       val context: String = "", val homograph: Boolean = false)

    private const val ACUTE = '́'
    private const val VOWELS = "аеёиоуыэюяАЕЁИОУЫЭЮЯ"
    private const val MAX_ITEMS = 40
    private val word = Regex("[+А-Яа-яЁё́]+")
    private val plusVowel = Regex("\\+([аеёиоуыэюяАЕЁИОУЫЭЮЯ])")

    private var decisions = mutableListOf<String>()
    /** Diagnostics: decisions since the previous call ("llm / offline -> chosen | sentence"). */
    @Synchronized fun takeDecisions(): List<String> = decisions.toList().also { decisions.clear() }
    @Volatile var lastOfflineChosen = 0; private set
    @Volatile var lastTieBreaks = 0; private set

    private fun ordinal(w: String): Int? {
        val mark = w.indexOf(ACUTE)
        if (mark <= 0 || w.indexOf(ACUTE, mark + 1) >= 0 || w[mark - 1] !in VOWELS) return null
        return w.substring(0, mark).count { it in VOWELS }
    }
    private fun bare(w: String) = w.replace(ACUTE.toString(), "").replace("+", "")
    private fun withStress(w: String, ordinal: Int): String {
        var n = 0
        val out = StringBuilder()
        for (c in bare(w)) { out.append(c); if (c in VOWELS && ++n == ordinal) out.append(ACUTE) }
        return out.toString()
    }
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

    /** Text without marks, for an independent offline opinion (ё and explicit '+' of the source stay). */
    fun unmarked(text: String) = text.replace(ACUTE.toString(), "")

    /** Words both sides stressed differently. [offline] is the offline-marked [unmarked] text of each fragment. */
    fun disputes(fragments: List<String>, offline: List<String>): List<Dispute> {
        val out = mutableListOf<Dispute>()
        for (f in fragments.indices) {
            val a = word.findAll(fragments[f]).toList()
            val b = word.findAll(plusVowel.replace(offline[f]) { it.groupValues[1] + ACUTE }).toList()
            if (a.size != b.size) continue
            for (i in a.indices) {
                val llm = a[i].value; val off = b[i].value
                if ('+' in llm || 'ё' in llm || 'Ё' in llm || llm.count { it in VOWELS } < 2) continue
                if (bare(llm).lowercase() != bare(off).lowercase().replace('ё', 'е')) continue
                val x = ordinal(llm) ?: continue
                val y = ordinal(off) ?: continue
                if (x != y) out += Dispute(f, a[i].range, sentence(fragments[f], a[i].range), llm, withStress(llm, y),
                    previousSentence(fragments[f], a[i].range))
            }
        }
        return out.take(MAX_ITEMS)
    }

    /** Homographs both models stressed the same way ("звучал о́рган… больной о́рган" — both wrong): the judge sees the
     * other variant too. [variants] gives the two stressed forms of a homograph (Silero's list), or null. */
    fun homographs(fragments: List<String>, existing: List<Dispute>, variants: (String) -> List<String>?): List<Dispute> {
        val taken = existing.map { it.fragment to it.range }.toSet()
        val out = mutableListOf<Dispute>()
        for (f in fragments.indices) for (m in word.findAll(fragments[f])) {
            val w = m.value
            if ((f to m.range) in taken || '+' in w || 'ё' in w || 'Ё' in w) continue
            val x = ordinal(w) ?: continue
            val pair = variants(bare(w)) ?: continue
            val ordinals = pair.map { ordinal(it) }
            if (x !in ordinals || ordinals.distinct().size != 2) continue
            val other = ordinals.first { it != x } ?: continue
            out += Dispute(f, m.range, sentence(fragments[f], m.range), w, withStress(w, other), previousSentence(fragments[f], m.range), homograph = true)
        }
        return out
    }

    /** Every dispute is asked twice, LLM option first and then offline option first. In a single binary ask the
     * model followed the option position about as often as the meaning (8 of 20 switches on «Идиот» ch. 1 were
     * wrong: гла́за, сло́е, де́дов, пи́сьма…); a mark changes only when both asks pick the offline variant.
     * The two orders go in two requests: duplicated items in one request came back merged. */
    fun items(disputes: List<Dispute>, offlineFirst: Boolean): List<Item> = disputes.map {
        Item(it.sentence, bare(it.llm), if (offlineFirst) listOf(it.offline, it.llm) else listOf(it.llm, it.offline), it.context)
    }

    /** Local Gemma: wherever it and the offline Silero Stress disagree, the offline mark wins. On the hard set the
     * offline chain alone scored 60/71, Gemma 54/71 and Gemma with a few-shot prompt 51/71: Gemma's own marks were
     * overriding correct ones (Раско́льников, Во́логде, о́ттепель). Words marked explicitly in [source] stay. */
    fun preferOffline(fragment: String, offline: String, source: String): String {
        val explicit = word.findAll(source).map { m -> m.value.any { it == '+' || it == ACUTE } }.toList()
        val words = word.findAll(fragment).toList()
        if (explicit.size != words.size) return fragment
        val out = StringBuilder(fragment)
        for (d in disputes(listOf(fragment), listOf(offline)).asReversed()) {
            val index = words.indexOfFirst { it.range == d.range }
            if (index >= 0 && !explicit[index]) out.replace(d.range.first, d.range.last + 1, d.offline)
        }
        return out.toString()
    }

    /** [choices]: answers to the LLM-first request followed by answers to the offline-first request. */
    private fun offlineChosen(choices: List<Int>, i: Int, n: Int) = choices[i] == 1 && choices[i + n] == 0

    /** Variant ordinal of a dispute's offline option, for the tie-breaker. */
    fun offlineOrdinal(d: Dispute): Int? = ordinal(d.offline)
    fun bareWord(d: Dispute): String = bare(d.llm)

    /** [tieBreak]: decides disputes the judge answered differently in the two orders (true = offline variant);
     * without it such words keep the LLM mark. */
    fun apply(fragments: List<String>, disputes: List<Dispute>, choices: List<Int>,
              tieBreak: (Dispute) -> Boolean = { false }): List<String> {
        val out = fragments.map { StringBuilder(it) }
        val log = mutableListOf<String>()
        // Right to left inside each fragment keeps the remaining ranges valid.
        for ((i, d) in disputes.withIndex().sortedByDescending { it.value.range.first }) {
            val consistent = choices[i] != choices[i + disputes.size]
            val chosenOffline = if (consistent) offlineChosen(choices, i, disputes.size) else !d.homograph && tieBreak(d)
            val chosen = if (chosenOffline) d.offline else d.llm
            log += "${if (d.homograph) "H " else ""}${d.llm} / ${d.offline} -> $chosen [${choices[i]}${choices[i + disputes.size]}] | ${d.sentence.take(120)}"
            if (chosenOffline) out[d.fragment].replace(d.range.first, d.range.last + 1, chosen)
        }
        synchronized(this) { decisions += log.reversed(); if (decisions.size > 500) decisions = decisions.takeLast(500).toMutableList() }
        lastOfflineChosen = disputes.indices.count { offlineChosen(choices, it, disputes.size) }
        lastTieBreaks = disputes.indices.count { choices[it] == choices[it + disputes.size] && !disputes[it].homograph && tieBreak(disputes[it]) }
        return out.map { it.toString() }
    }
}
