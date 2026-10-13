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
     * [llm] / [silero]: the stressed-vowel ordinals the LLM and Silero gave the word, if any. */
    data class Dispute(val fragment: Int, val range: IntRange, val sentence: String, val first: String, val second: String,
                       val context: String = "", val homograph: Boolean = false, val llm: Int? = null, val silero: Int? = null,
                       val yo: Boolean = false)
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

    /** The marked occurrences of [source]: bare lowercase word -> its 0-based occurrence numbers that carry '+' or
     * U+0301. Only that occurrence is kept: «У за́мка нет замка» still checks the second «замка». */
    fun explicitOccurrences(source: String): Map<String, Set<Int>> {
        val seen = HashMap<String, Int>(); val out = HashMap<String, MutableSet<Int>>()
        for (m in word.findAll(source)) {
            val k = bare(m.value).lowercase(); val n = seen[k] ?: 0; seen[k] = n + 1
            if (m.value.any { it == '+' || it == ACUTE }) out.getOrPut(k) { HashSet() } += n
        }
        return out
    }

    /** Bare lowercase words of [fragment] whose ё the LLM added: [source] spells them only with е. */
    fun addedYo(source: String, fragment: String): Set<String> {
        val src = word.findAll(source).map { bare(it.value).lowercase() }.toSet()
        return word.findAll(fragment).map { bare(it.value).lowercase() }
            .filter { 'ё' in it && it !in src && it.replace('ё', 'е') in src }.toSet()
    }
    private fun capitalised(w: String) = w.first().isUpperCase() && w.drop(1).any { it.isLowerCase() }

    /** ё an LLM added ([text] against [source], word by word) is kept only when [confirmed] (bare lowercase е-form,
     * its ё-form) says so; otherwise the word is spelled with е again (keeping its mark). Local Gemma wrote
     * «телёфон»; the cloud's added ё goes to the judge in [plan] instead. */
    fun confirmYo(source: String, text: String, confirmed: (String, String) -> Boolean): String {
        val a = word.findAll(source).toList()
        val b = word.findAll(text).toList()
        if (a.size != b.size) return text
        val out = StringBuilder(text)
        for (i in b.indices.reversed()) {
            val w = b[i].value
            if ('ё' !in w && 'Ё' !in w) continue
            val src = bare(a[i].value)
            if ('ё' in src || 'Ё' in src || key(w) != key(src)) continue
            val yoForm = bare(w).lowercase()
            if (confirmed(yoForm.replace('ё', 'е'), yoForm)) continue
            out.replace(b[i].range.first, b[i].range.last + 1, w.replace('ё', 'е').replace('Ё', 'Е'))
        }
        return out.toString()
    }

    /** [fragments]: the LLM's text with its own marks, or the plain text without an LLM. [silero]: Silero Stress on
     * [unmarked] of the same fragment. [dictionary]: RUAccent's ordinal of a bare lowercase word (null = unknown).
     * [homograph]: the variant ordinals of a RUAccent homograph. [keep]: words never touched (fragment, bare lowercase,
     * occurrence number of that word in the fragment). [llmAsDictionary]: the dictionary is not downloaded yet; the
     * LLM's own mark stands in for it.
     * [knownName]: set for an LLM that names people reliably (the cloud). A capitalised word inside a sentence that the
     * names dictionary does not know is a name: the two dictionaries only know the common word spelled the same
     * (база́ров, ша́риков, сомо́в for Ба́заров, Шарико́в, Со́мов) and Silero's accentor only guesses (Ферды́щенко). Their
     * opinion is then not final against the LLM: the judge decides, a tie keeps the LLM's mark.
     * [bookName]: a character's word form of the prepared book: a name at the start of a sentence too.
     * [addedYo]: words whose ё the LLM added (fragment, bare lowercase). When RUAccent knows the word with the stress on
     * another vowel and no ё («рассе́рженный» for «рассержённый») the judge chooses; a tie takes the dictionary. */
    fun plan(fragments: List<String>, silero: List<String>, dictionary: (String) -> Int?, homograph: (String) -> List<Int>?,
             keep: (Int, String, Int) -> Boolean = { _, _, _ -> false }, llmAsDictionary: Boolean = false,
             knownName: ((String) -> Boolean)? = null, addedYo: (Int, String) -> Boolean = { _, _ -> false },
             bookName: (String) -> Boolean = { false }): Plan {
        val marks = mutableListOf<Mark>()
        val disputes = mutableListOf<Dispute>()
        var agreed = 0; var single = 0; var llmOnly = 0
        for (f in fragments.indices) {
            val a = word.findAll(fragments[f]).toList()
            val b = word.findAll(silero[f]).toList()
            val aligned = a.size == b.size && a.indices.all { key(a[it].value) == key(b[it].value) }
            val occurrences = HashMap<String, Int>()
            for (i in a.indices) {
                val w = a[i].value
                val k = bare(w).lowercase()
                val occurrence = occurrences[k] ?: 0
                occurrences[k] = occurrence + 1
                if ('+' in w || w.count { it in VOWELS } < 2 || keep(f, k, occurrence)) continue
                val s = if (aligned) ordinal(b[i].value) else null
                if ('ё' in w || 'Ё' in w) {
                    // A name inside a sentence keeps the LLM's ё (Королёв, Пугачёв): RUAccent knows the common words.
                    if (!addedYo(f, k) || (capitalised(w) && !com.brahmadeo.supertonic.tts.utils.SentenceStart.at(fragments[f], a[i].range.first))) continue
                    val plainE = bare(w).replace('ё', 'е').replace('Ё', 'Е')
                    val yoAt = bare(w).let { v -> v.substring(0, v.indexOfFirst { it == 'ё' || it == 'Ё' } + 1).count { it in VOWELS } }
                    val r = if (homograph(plainE.lowercase()) != null) null else dictionary(plainE.lowercase())
                    if (r != null && r != yoAt) disputes += Dispute(f, a[i].range, sentence(fragments[f], a[i].range),
                        withStress(plainE, r), bare(w), previousSentence(fragments[f], a[i].range), silero = s, yo = true)
                    continue
                }
                val variants = homograph(k)?.distinct()?.takeIf { it.size >= 2 }
                val llm = ordinal(w)
                val r = if (variants != null) null else dictionary(k) ?: if (llmAsDictionary) llm else null
                fun dispute(x: Int, y: Int, homo: Boolean) { disputes += Dispute(f, a[i].range, sentence(fragments[f], a[i].range),
                    withStress(w, x), withStress(w, y), previousSentence(fragments[f], a[i].range), homo, llm, s) }
                val unknownName = knownName != null && llm != null && variants == null && capitalised(w) && !knownName(bare(w)) &&
                    (bookName(bare(w)) || !com.brahmadeo.supertonic.tts.utils.SentenceStart.at(fragments[f], a[i].range.first))
                when {
                    unknownName -> {
                        val other = listOfNotNull(r, s).firstOrNull { it != llm }
                        when {
                            other != null -> dispute(llm!!, other, false)
                            r != null || s != null -> { marks += Mark(f, a[i].range, withStress(w, llm!!)); agreed++ }
                            else -> llmOnly++
                        }
                    }
                    r != null && s != null && r == s -> { marks += Mark(f, a[i].range, withStress(w, r)); agreed++ }
                    r != null && s != null -> dispute(r, s, false)
                    variants != null -> {
                        // Silero's variant first (or the LLM's, or the dictionary's first), against the LLM's other
                        // variant or the next one: a homograph of 3-4 variants is asked as well. A Silero mark outside
                        // RUAccent's variants is not an opinion about this homograph.
                        val m = s?.takeIf { it in variants }
                        val l = llm?.takeIf { it in variants }
                        val x = m ?: l ?: variants[0]
                        val y = (if (m != null) l?.takeIf { it != m } else null) ?: variants.first { it != x }
                        dispute(x, y, true)
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
     * homograph). [tie] may decide first (names, Silero's unambiguous words); null = no opinion. */
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
