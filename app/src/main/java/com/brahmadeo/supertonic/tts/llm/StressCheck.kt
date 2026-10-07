package com.brahmadeo.supertonic.tts.llm

/** Cross-check of the cloud LLM's stress marks. The offline Silero Stress (context BERT for homographs,
 * dictionary + accentor for the rest) marks the same text independently; words where the two disagree go
 * back to the LLM with their sentence and both options (LlmProviders.verifyStress), and its choice is final.
 * On the first chapter of «Идиот» this is where the LLM's slips were (оттепе́ль, на́хальною, субъекте́,
 * нача́л for the verb); its strengths, names and homographs, it keeps when asked again. Any failure keeps
 * the LLM's original marks. */
object StressCheck {
    data class Item(val sentence: String, val word: String, val options: List<String>)
    /** One disagreement: [fragment] index, word [range] in that fragment, the two variants. */
    data class Dispute(val fragment: Int, val range: IntRange, val sentence: String, val llm: String, val offline: String)

    private const val ACUTE = '́'
    private const val VOWELS = "аеёиоуыэюяАЕЁИОУЫЭЮЯ"
    private const val MAX_ITEMS = 40
    private val word = Regex("[+А-Яа-яЁё́]+")
    private val plusVowel = Regex("\\+([аеёиоуыэюяАЕЁИОУЫЭЮЯ])")

    private var decisions = mutableListOf<String>()
    /** Diagnostics: decisions since the previous call ("llm / offline -> chosen | sentence"). */
    @Synchronized fun takeDecisions(): List<String> = decisions.toList().also { decisions.clear() }
    @Volatile var lastOfflineChosen = 0; private set

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
    private fun sentence(text: String, range: IntRange): String {
        var from = range.first
        while (from > 0 && text[from - 1] !in ".!?…\n" && range.first - from < 220) from--
        var to = range.last + 1
        while (to < text.length && text[to - 1] !in ".!?…\n" && to - range.last < 220) to++
        return text.substring(from, to).replace(ACUTE.toString(), "").trim()
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
                if (x != y) out += Dispute(f, a[i].range, sentence(fragments[f], a[i].range), llm, withStress(llm, y))
            }
        }
        return out.take(MAX_ITEMS)
    }

    /** Every dispute is asked twice, LLM option first and then offline option first. In a single binary ask the
     * model followed the option position about as often as the meaning (8 of 20 switches on «Идиот» ch. 1 were
     * wrong: гла́за, сло́е, де́дов, пи́сьма…); a mark changes only when both asks pick the offline variant. */
    fun items(disputes: List<Dispute>): List<Item> =
        disputes.map { Item(it.sentence, bare(it.llm), listOf(it.llm, it.offline)) } +
        disputes.map { Item(it.sentence, bare(it.llm), listOf(it.offline, it.llm)) }

    private fun offlineChosen(choices: List<Int>, i: Int, n: Int) = choices[i] == 1 && choices[i + n] == 0

    fun apply(fragments: List<String>, disputes: List<Dispute>, choices: List<Int>): List<String> {
        val out = fragments.map { StringBuilder(it) }
        val log = mutableListOf<String>()
        // Right to left inside each fragment keeps the remaining ranges valid.
        for ((i, d) in disputes.withIndex().sortedByDescending { it.value.range.first }) {
            val chosenOffline = offlineChosen(choices, i, disputes.size)
            val chosen = if (chosenOffline) d.offline else d.llm
            log += "${d.llm} / ${d.offline} -> $chosen [${choices[i]}${choices[i + disputes.size]}] | ${d.sentence.take(120)}"
            if (chosenOffline) out[d.fragment].replace(d.range.first, d.range.last + 1, chosen)
        }
        synchronized(this) { decisions += log.reversed(); if (decisions.size > 500) decisions = decisions.takeLast(500).toMutableList() }
        lastOfflineChosen = disputes.indices.count { offlineChosen(choices, it, disputes.size) }
        return out.map { it.toString() }
    }
}
