package com.brahmadeo.supertonic.tts.books.prepare

import com.brahmadeo.supertonic.tts.books.BookContext
import com.brahmadeo.supertonic.tts.books.BookPackage
import com.brahmadeo.supertonic.tts.books.ReplyIndex
import com.brahmadeo.supertonic.tts.llm.VoiceRoleProtocol
import com.brahmadeo.supertonic.tts.llm.VoiceRoleText
import org.json.JSONArray
import org.json.JSONObject

/** «Разметка реплик» of a prepared book: the reading-time role request ([VoiceRoleProtocol]) over the dialogue
 * paragraphs of each section, with that section's characters, in batches of [BATCH_CHARS]; the voices of every word go
 * into a [ReplyIndex]. Narration paragraphs need no request (the author reads them) but still feed the word window.
 * On «Идиот» (711 k characters of dialogue): 158 requests, ~8 minutes, 99 % of the dialogue paragraphs marked. */
internal object ReplyMarking {
    const val BATCH_CHARS = 5500
    private const val CONTEXT_CHARS = 1200
    /** Narration between two lines up to this length goes into the request: it names who speaks («ответил он»). */
    private const val GAP_CHARS = 1500
    private val firstPersonWord = Regex("(?iu)(?<![\\p{L}])(я|мне|меня|мной|мой|моя|моё|мое|мои|моего|моей|моих)(?![\\p{L}])")

    /** A section narrated in the first person: «я» in a third or more of its narration paragraphs. */
    fun firstPerson(texts: List<String>): Boolean {
        val narration = texts.filter { !ReplyIndex.isDialogue(it) && it.length > 40 }
        return narration.size >= 3 && narration.count { firstPersonWord.containsMatchIn(it) } * 3 >= narration.size
    }

    data class Batch(val section: String, val paragraphs: List<Int>, val prompt: String)
    data class Result(val replies: JSONObject, val marked: Int, val failed: Int, val words: Int)

    /** The requests, in reading order. */
    fun batches(sections: List<EpubBook.Section>, paragraphs: List<EpubBook.Paragraph>, pkg: BookPackage): List<Batch> {
        val out = mutableListOf<Batch>()
        for ((index, section) in sections.withIndex()) {
            val cast = pkg.castOf(section.id) ?: BookPackage.Cast(emptyList(), emptyList(), emptyList())
            val context = BookContext(0, section.id, cast, emptyMap())
            val inSection = paragraphs.indices.filter { paragraphs[it].section == index }
            val firstPerson = firstPerson(inSection.map { paragraphs[it].text })
            // Each request: lines with the narration between them (when short), so the model sees who speaks.
            val groups = mutableListOf<MutableList<Int>>()
            var current: MutableList<Int>? = null
            var pending = mutableListOf<Int>()         // narration after the last line of the current group
            for (i in inSection) {
                val text = paragraphs[i].text
                if (!ReplyIndex.isDialogue(text)) { if (current != null) pending += i; continue }
                val size = current?.sumOf { paragraphs[it].text.length } ?: 0
                val gap = pending.sumOf { paragraphs[it].text.length }
                if (current == null || gap > GAP_CHARS || size + gap + text.length > BATCH_CHARS) {
                    current = mutableListOf(i); groups += current
                } else { current.addAll(pending); current.add(i) }
                pending = mutableListOf()
            }
            for (g in groups) {
                val before = g.first().let { first -> inSection.filter { it < first }.reversed()
                    .fold("") { acc, k -> if (acc.length >= CONTEXT_CHARS) acc else paragraphs[k].text + "\n" + acc }.takeLast(CONTEXT_CHARS) }
                out += Batch(section.id, g, VoiceRoleProtocol.prompt(g.map { paragraphs[it].text }, before, context, firstPerson))
            }
        }
        return out
    }

    /** Runs [batches] through [ask] (one at a time; an answer that cannot be parsed counts its paragraphs as failed). */
    fun mark(paragraphs: List<EpubBook.Paragraph>, batches: List<Batch>, pkg: BookPackage, ask: (Batch) -> String,
             progress: (Int, Int) -> Unit = { _, _ -> }, check: () -> Unit = {}): Result {
        val plans = arrayOfNulls<List<VoiceRoleText>>(paragraphs.size)
        var marked = 0; var failed = 0
        for ((n, batch) in batches.withIndex()) {
            check(); progress(n, batches.size)
            val texts = batch.paragraphs.map { paragraphs[it].text }
            val cast = pkg.castOf(batch.section) ?: BookPackage.Cast(emptyList(), emptyList(), emptyList())
            val result = runCatching { VoiceRoleProtocol.parseValidated(ask(batch), texts, BookContext(0, batch.section, cast, emptyMap())) }
                .getOrElse { e -> if (e is java.util.concurrent.CancellationException) throw e; List(texts.size) { null } }
            // Narration sent along for context is the author's anyway: only the lines are kept and counted.
            for ((k, plan) in result.withIndex()) {
                val i = batch.paragraphs[k]
                if (!ReplyIndex.isDialogue(paragraphs[i].text)) continue
                if (plan == null) failed++ else { marked++; plans[i] = plan }
            }
        }
        progress(batches.size, batches.size)
        val builder = ReplyIndex.Companion.Builder()
        for ((i, p) in paragraphs.withIndex()) builder.add(p.text, plans[i])
        val (codes, index) = builder.export()
        return Result(JSONObject().put("algorithm", ReplyIndex.ALGORITHM).put("codes", JSONArray(codes)).put("index", index),
            marked, failed, builder.size)
    }
}
