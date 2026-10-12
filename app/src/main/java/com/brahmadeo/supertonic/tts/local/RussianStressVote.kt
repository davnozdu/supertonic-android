package com.brahmadeo.supertonic.tts.local

import android.content.Context
import android.util.Log
import com.brahmadeo.supertonic.tts.llm.StressCheck
import com.brahmadeo.supertonic.tts.llm.StressJudgeDictionary

/** Android side of [StressCheck]: Silero's opinion, RUAccent's dictionary (the full .sacc of [StressJudgeDictionary])
 * and its homograph variants (assets/ruaccent_omographs.tsv.gz, tools/ruaccent/build_omographs.py). */
object RussianStressVote {
    @Volatile private var homographs: Map<String, List<Int>>? = null

    private fun homographs(ctx: Context): Map<String, List<Int>> = homographs ?: synchronized(this) {
        homographs ?: runCatching {
            LocalRussianAssets.openTable(ctx, "ruaccent_omographs.tsv").useLines { lines ->
                lines.mapNotNull { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1).split(',').map { it.toInt() }
                }.toMap(HashMap(40_000))
            }.also { Log.i("StressVote", "RUAccent homographs: ${it.size}") }
        }.getOrElse { Log.w("StressVote", "RUAccent homographs unavailable: ${it.javaClass.simpleName}"); emptyMap() }
            .also { homographs = it }
    }

    fun homographVariants(ctx: Context, word: String): List<Int>? = homographs(ctx)[word.lowercase()]

    /** Silero Stress downloaded and not switched off («Локальные ударения и ё по контексту»). */
    fun sileroEnabled(ctx: Context) = LocalRussianAssets.ready(ctx) &&
        ctx.getSharedPreferences("SupertonicPrefs", 0).getBoolean("local_russian_stress", true)

    /** Silero Stress (with the user lexicon and the names dictionary) on [text] without the LLM's marks. Without
     * Silero the LLM's own text stands in for its opinion, so a disagreement with RUAccent still reaches the judge. */
    fun silero(ctx: Context, text: String): String = if (!sileroEnabled(ctx)) text else
        com.brahmadeo.supertonic.tts.utils.RussianNames.overlay(ctx, com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.apply(
            LocalRussianStress.apply(ctx, StressCheck.unmarked(text)), "ru"))

    /** RUAccent's dictionary ready (downloaded once, unmetered network); false = the download is started. */
    fun dictionaryReady(ctx: Context) = StressJudgeDictionary.ensure(ctx)

    /** [fragments] with the LLM's marks ([sources] give the book's own explicit marks) planned against [silero]. */
    fun plan(ctx: Context, fragments: List<String>, silero: List<String>, sources: List<String>): StressCheck.Plan {
        val ready = dictionaryReady(ctx)
        val explicit = sources.map { StressCheck.explicitWords(it) }
        return StressCheck.plan(fragments, silero, { if (ready) StressJudgeDictionary.ordinal(it) else null },
            { homographVariants(ctx, it) }, { f, w -> w in explicit.getOrElse(f) { emptySet() } }, llmAsDictionary = !ready)
    }

    /** Tie-break for a judge that contradicts itself (or is unavailable): a capitalised name takes the names
     * dictionary's stress; a RUAccent homograph that Silero knows as an ordinary word (досыта, дедов, Крыму: its
     * dictionary, not its homograph model) keeps Silero's mark. Homographs for both (мука, атлас, письма) are left to
     * the LLM's own mark: on the probe runs the judge's ties there were right with the LLM and wrong with Silero. */
    fun tie(ctx: Context): (StressCheck.Dispute) -> Int? = { d ->
        val word = d.first.replace("́", "")
        val name = if (word.first().isUpperCase()) com.brahmadeo.supertonic.tts.utils.RussianNames.ordinal(word) else null
        name ?: d.silero.takeIf { d.homograph && it != null && sileroEnabled(ctx) && !LocalRussianStress.isHomograph(ctx, word) }
    }

    /** No LLM: the two dictionaries alone. Words already marked in [text] (book, lexicon, names, an imported
     * dictionary) stay. */
    fun offline(ctx: Context, text: String): String {
        if (!sileroEnabled(ctx)) return text
        val silero = LocalRussianStress.apply(ctx, text)
        // Silero's ё (its safe dictionary, «Семёна») first: a word with ё needs no mark and is left out of the vote.
        val withYo = com.brahmadeo.supertonic.tts.llm.MissingSpeechMarks.merge(text, silero, stress = false, yo = true)
        val ready = dictionaryReady(ctx)
        val explicit = StressCheck.explicitWords(text)
        val plan = StressCheck.plan(listOf(withYo), listOf(silero), { if (ready) StressJudgeDictionary.ordinal(it) else null },
            { homographVariants(ctx, it) }, { _, w -> w in explicit })
        return StressCheck.resolve(plan, null, tie(ctx)).single()
    }
}
