package com.brahmadeo.supertonic.tts.llm

import android.content.Context
import com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache
import java.util.concurrent.Executors

/** The LLM for a reader that hands over one sentence at a time (KOReader with audiobook.koplugin): the plugin sends the
 * pages it is about to read through SmartBookProvider («prepare»), their paragraphs are prepared in the background by
 * the same LLM chain as Moon+'s queue (stress with its check, ё, punctuation, numbers, voices), and a sentence the reader
 * then sends for synthesis is cut out of its prepared paragraph ([SentenceAlign]). A sentence not prepared in time is
 * read at once by the dictionaries, as before: no pause is ever added. Only while the plugin reads aloud. */
object KoreaderAhead {
    private val caller = Any()
    private const val KEEP = 160
    private class Prepared(val aligned: SentenceAlign.Aligned, val plan: List<VoiceRoleText>, val generation: Long)
    /** By the paragraph as the plugin sent it, oldest first. */
    private val prepared = LinkedHashMap<String, Prepared>()
    private val pending = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "KoreaderAhead").apply { isDaemon = true } }
    private val cyrillic = Regex("[А-Яа-яЁё]")

    /** The pages about to be read: their paragraphs go to the LLM (those not prepared yet). */
    fun prepare(context: Context, text: String) {
        val ctx = context.applicationContext
        if (!LlmPreparation.enabled(ctx)) return
        val paragraphs = text.split('\n').map { it.trim() }.filter { it.length in 2..6000 && cyrillic.containsMatchIn(it) }.distinct()
        val generation = SpeechPreparationCache.generation
        val todo = synchronized(this) {
            paragraphs.filter { p -> prepared[p]?.generation != generation && p !in pending }.also { pending += it }
        }
        if (todo.isEmpty()) return
        val ids = todo.map { it to LlmPreparation.submit(ctx, caller, it) }
        worker.execute {
            for ((paragraph, id) in ids) try {
                if (id == null) continue
                val result = LlmPreparation.prepareResult(ctx, paragraph, id, timeoutMs = 90_000)
                if (result.fallback) continue
                val aligned = SentenceAlign.Aligned(paragraph, result.text)
                // A badly matched answer is not used: its sentences are read by the dictionaries.
                if (aligned.matchedShare < 0.7) continue
                synchronized(this) {
                    prepared.remove(paragraph)
                    prepared[paragraph] = Prepared(aligned, result.voicePlan, generation)
                    while (prepared.size > KEEP) prepared.remove(prepared.keys.first())
                }
            } catch (_: Exception) {
            } finally { synchronized(this) { pending -= paragraph } }
            com.brahmadeo.supertonic.tts.utils.DiagLog.i("KoreaderAhead", "Prepared paragraphs=${ids.size} kept=${synchronized(this) { prepared.size }}")
        }
    }

    /** The prepared form of a sentence the reader sends now, or null (not prepared yet, or settings changed since). */
    internal fun sentence(text: String): PreparedSpeechText? {
        val generation = SpeechPreparationCache.generation
        val candidates = synchronized(this) { prepared.values.filter { it.generation == generation }.reversed() }
        for (p in candidates) {
            val cut = p.aligned.cut(text) ?: continue
            if (cut.text.isBlank()) continue
            return PreparedSpeechText(cut.text, true, SentenceAlign.clipPlan(p.aligned.prepared, p.plan, cut))
        }
        return null
    }
}
