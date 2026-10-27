package com.brahmadeo.supertonic.tts.llm

import android.content.Context
import com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache
import java.util.concurrent.Executors

/** Reading ahead for a reader that hands over one sentence at a time (KOReader with audiobook.koplugin). The plugin
 * sends through SmartBookProvider («prepare») the text from the place being read on (up to «Упреждение», the same
 * limit as Moon+'s queue) and the exact sentences it will send. Their paragraphs are prepared in the background by the
 * same LLM chain as Moon+'s (stress with its check, ё, punctuation, numbers, voices) in bigger, slower batches (half the
 * requests); a paragraph the LLM cannot do (no connection, LLM off) is read by the dictionaries, its voices guessed on
 * the phone for the whole paragraph ([LocalSpeakers]: a sentence alone loses the reply it continues). Each sentence then gets
 * its audio made ahead into the RAM cache ([ReaderAudioAhead.submitPrepared]) with the voice and rate of the reader's
 * own requests: when it comes, it plays at once, and reading goes on through a dropped connection while the buffer
 * lasts. A sentence not ready yet is still read at once (dictionaries): no pause is ever added. Everything is in RAM
 * and only while the plugin reads aloud. */
object KoreaderAhead {
    internal val caller = Any()
    private const val KEEP = 200
    private class Prepared(val aligned: SentenceAlign.Aligned, val plan: List<VoiceRoleText>, val llm: Boolean, val generation: Long,
                           val at: Long = android.os.SystemClock.elapsedRealtime())
    /** A paragraph the LLM could not do is asked again after this long (a dropped connection is not for good). */
    private const val RETRY_MS = 120_000L
    /** By the paragraph as the plugin sent it, oldest first. */
    private val prepared = LinkedHashMap<String, Prepared>()
    private val pending = HashSet<String>()
    /** Sentences the plugin will send, in order, whose audio is not made yet. */
    private val upcoming = LinkedHashSet<String>()
    /** Sentences whose audio was asked for ahead, until the plugin's list no longer has them. */
    private val submitted = LinkedHashSet<String>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "KoreaderAhead").apply { isDaemon = true } }
    /** Audio ahead is queued here, not behind [worker]'s wait for the LLM (up to minutes): a page turn starts it at once. */
    private val audio = Executors.newSingleThreadExecutor { Thread(it, "KoreaderAheadAudio").apply { isDaemon = true } }
    private val cyrillic = Regex("[А-Яа-яЁё]")
    private val speakers = LocalSpeakers()
    /** The reader app (its requests teach the voice and the rate the ahead audio must have to be found in the cache). */
    @Volatile var readerUid = -1
    @Volatile private var readerVoice: String? = null
    @Volatile private var readerRate = 0f

    /** The reader's own synthesis request: ahead audio is made with the same voice and rate. */
    fun observe(voiceName: String?, rate: Float) { readerVoice = voiceName; readerRate = rate }

    /** The text from the place being read on, and the sentences the reader will send (in order). */
    fun prepare(context: Context, text: String, sentences: List<String>) {
        val ctx = context.applicationContext
        val limit = LlmSettings.aheadChars(ctx)
        val paragraphs = mutableListOf<String>()
        var total = 0
        for (p in text.split('\n').map { it.trim() }.filter { it.length in 2..6000 && cyrillic.containsMatchIn(it) }.distinct()) {
            if (total > 0 && total + p.length > limit) break
            paragraphs += p; total += p.length
        }
        val generation = SpeechPreparationCache.generation
        val llm = LlmPreparation.enabled(ctx)
        val todo = synchronized(this) {
            // The plugin sends the sentences from the one being read on: they replace the old list, so after a jump the
            // audio of the place left is not made first.
            upcoming.clear()
            var chars = 0
            for (s in sentences) { if (chars > limit || upcoming.size >= 400) break; chars += s.length; upcoming += s }
            // Read already or left behind: their ahead audio goes.
            val gone = submitted.filter { it !in upcoming }
            submitted.removeAll(gone.toSet())
            if (gone.isNotEmpty()) audio.execute { ReaderAudioAhead.drop(gone) }
            val now = android.os.SystemClock.elapsedRealtime()
            paragraphs.filter { p ->
                val old = prepared[p]
                p !in pending && (old == null || old.generation != generation || (llm && !old.llm && now - old.at > RETRY_MS))
            }.also { pending += it }
        }
        if (todo.isEmpty()) { audio.execute { feedAudio(ctx) }; return }
        val ids = todo.map { it to (if (llm) LlmPreparation.submit(ctx, caller, it) else null) }
        worker.execute {
            for ((paragraph, id) in ids) try {
                val result = id?.let { LlmPreparation.prepareResult(ctx, paragraph, it, timeoutMs = 120_000, resubmit = false) }
                val aligned = result?.takeIf { !it.fallback }?.let { SentenceAlign.Aligned(paragraph, it.text) }
                // No LLM answer (offline, LLM off) or a badly matched one: the dictionaries read this paragraph.
                val good = aligned != null && aligned.matchedShare >= 0.7
                store(paragraph, if (good) Prepared(aligned!!, result!!.voicePlan, true, generation)
                    else Prepared(SentenceAlign.Aligned(paragraph, paragraph), localVoices(ctx, paragraph), false, generation))
                audio.execute { feedAudio(ctx) }
            } catch (_: Exception) {
            } finally { synchronized(this) { pending -= paragraph } }
            com.brahmadeo.supertonic.tts.utils.DiagLog.i("KoreaderAhead", "Prepared paragraphs=${ids.size} upcoming=${synchronized(this) { upcoming.size }}")
        }
    }

    /** Voices of a paragraph read without the LLM, guessed for the whole paragraph. A prepared book being read keeps its
     * own reply marking (the reader's sentences find it), so nothing is guessed then. */
    private fun localVoices(ctx: Context, paragraph: String): List<VoiceRoleText> {
        if (!LlmSettings.multiVoiceEnabled(ctx) || !com.brahmadeo.supertonic.tts.utils.AssetManager.isRussianModel(ctx)) return emptyList()
        if (com.brahmadeo.supertonic.tts.books.BookMatcher.recognised.value != null) return emptyList()
        return runCatching { speakers.plan(paragraph) }.getOrDefault(emptyList())
    }

    private fun store(paragraph: String, p: Prepared) = synchronized(this) {
        prepared.remove(paragraph)
        prepared[paragraph] = p
        while (prepared.size > KEEP) prepared.remove(prepared.keys.first())
    }

    /** Audio ahead for the upcoming sentences in order, as far as their paragraphs are ready. */
    private fun feedAudio(ctx: Context) {
        if (readerRate <= 0f) return // no request of the reader seen yet: its voice and rate are unknown
        while (true) {
            val next = synchronized(this) { upcoming.firstOrNull() } ?: return
            val ready = find(next)
            if (ready == null) {
                // Its paragraph is still with the LLM: keep the order. Found nowhere with nothing pending (a sentence over
                // a page break): it is made on demand, the rest goes on.
                if (synchronized(this) { pending.isNotEmpty() }) return
                synchronized(this) { upcoming.remove(next) }
                continue
            }
            synchronized(this) { upcoming.remove(next); submitted += next; while (submitted.size > 600) submitted.remove(submitted.first()) }
            runCatching { ReaderAudioAhead.submitPrepared(ctx, next, ready, readerVoice, readerRate) }
        }
    }

    private fun find(text: String): PreparedSpeechText? {
        val generation = SpeechPreparationCache.generation
        val candidates = synchronized(this) { prepared.values.filter { it.generation == generation }.reversed() }
        for (p in candidates) {
            val cut = p.aligned.cut(text) ?: continue
            if (cut.text.isBlank()) continue
            return PreparedSpeechText(cut.text, p.llm, SentenceAlign.clipPlan(p.aligned.prepared, p.plan, cut))
        }
        return null
    }

    /** The prepared form of a sentence the reader sends now (its audio was not made ahead): by the LLM, or by the
     * dictionaries with the voices guessed for its whole paragraph. Null: read as before (one sentence alone). */
    internal fun sentence(ctx: Context, text: String): PreparedSpeechText? =
        find(text)?.takeIf { it.llmProcessed || it.voicePlan.isNotEmpty() } ?: pendingSentence(ctx, text)

    /** A sentence whose paragraph is still with the LLM: read now by the dictionaries (voices guessed for its paragraph)
     * instead of a request of its own for the same words and a wait of up to 1.5 s for it. */
    private fun pendingSentence(ctx: Context, text: String): PreparedSpeechText? {
        val paragraph = synchronized(this) { pending.toList() }.firstOrNull { text.trim().length > 1 && text.trim() in it } ?: return null
        val aligned = SentenceAlign.Aligned(paragraph, paragraph)
        val cut = aligned.cut(text)?.takeIf { it.text.isNotBlank() } ?: return null
        val plan = if (LlmSettings.multiVoiceEnabled(ctx) && com.brahmadeo.supertonic.tts.utils.AssetManager.isRussianModel(ctx) &&
            com.brahmadeo.supertonic.tts.books.BookMatcher.recognised.value == null)
            runCatching { LocalSpeakers().plan(paragraph) }.getOrDefault(emptyList()) else emptyList()
        return PreparedSpeechText(cut.text, false, SentenceAlign.clipPlan(paragraph, plan, cut))
    }
}
