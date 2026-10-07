package com.brahmadeo.supertonic.tts.llm

import android.content.Context
import android.os.Bundle
import com.brahmadeo.supertonic.tts.SupertonicTTS
import com.brahmadeo.supertonic.tts.utils.AssetManager
import com.brahmadeo.supertonic.tts.utils.TextNormalizer
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/** Pre-synthesize only requests really queued by a reader. Never acknowledge playback early. */
object ReaderAudioAhead {
    private val epoch = AtomicLong()
    // All Russian engines use the same rolling window; never silently discard queued work.
    private val worker=ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,ArrayBlockingQueue(256),
        { Thread(it,"ReaderAudioAhead").apply { isDaemon=true } },ThreadPoolExecutor.AbortPolicy())
    private val queuedChars=java.util.concurrent.atomic.AtomicInteger()
    private class AheadTask(val chars: Int,val work: () -> Unit): Runnable {
        private val released=java.util.concurrent.atomic.AtomicBoolean()
        fun release() { if(released.compareAndSet(false,true)) queuedChars.addAndGet(-chars) }
        override fun run() { try { work() } finally { release() } }
    }
    private val preparedTexts=PreparedSpeechHandoff(256)
    private val delivered=linkedSetOf<String>()
    private data class Snapshot(val text: String, val model: String, val voice: String, val rate: Float,
        val steps: Int, val generation: Long, val cacheGeneration: Long, val requestedVoice: String? = null)
    private val snapshots = linkedMapOf<String, Snapshot>()
    @Synchronized private fun isDelivered(owner: String) = owner in delivered
    @Synchronized private fun isCurrent(owner: String, snapshot: Snapshot) = snapshots[owner] === snapshot
    @Synchronized fun cancel() {
        epoch.incrementAndGet(); preparedTexts.clear(); delivered.clear(); snapshots.clear()
        val waiting=ArrayList<Runnable>();worker.queue.drainTo(waiting)
        waiting.forEach { (it as? AheadTask)?.release() }
    }
    @Synchronized internal fun takePrepared(text: String): PreparedSpeechText? {
        val queued = preparedTexts.takePrepared(text)
        val latest = LlmPreparation.takeForPlayback(text)
        val prepared = latest?.let { PreparedSpeechText(it.text, !it.fallback, it.voicePlan) } ?: queued
        if (latest == null && queued != null) LlmPreparation.consumed(text)
        val owner = SpeechTextTrace.fingerprint(text)
        snapshots.remove(owner)
        delivered.add(owner)
        while (delivered.size > 1024) delivered.remove(delivered.first())
        SupertonicTTS.releaseAheadCache(owner)
        return prepared
    }
    @Synchronized internal fun refreshPrepared(ctx: Context, source: String, result: LlmPreparation.Result) {
        val owner = SpeechTextTrace.fingerprint(source)
        val snapshot = snapshots[owner] ?: return
        if (isDelivered(owner) || snapshot.generation != epoch.get()) return
        val prepared = PreparedSpeechText(result.text, !result.fallback, result.voicePlan)
        if (!preparedTexts.replace(source, prepared)) return
        val refreshed = snapshot.copy()
        snapshots[owner] = refreshed
        SupertonicTTS.releaseAheadCache(owner)
        android.util.Log.i("ReaderAhead", "Upgraded unplayed roles source=$owner provider=${result.roleProvider}")
        queuedChars.addAndGet(source.length)
        val task = AheadTask(source.length) { synthesizeAhead(ctx.applicationContext, refreshed, prepared) }
        try { worker.execute(task) } catch (_: java.util.concurrent.RejectedExecutionException) { task.release() }
    }
    private fun synthesizeAhead(context: Context, snapshot: Snapshot, prepared: PreparedSpeechText) {
        val owner = SpeechTextTrace.fingerprint(snapshot.text)
        fun obsolete() = isDelivered(owner) || !isCurrent(owner, snapshot) || snapshot.generation != epoch.get() ||
            snapshot.cacheGeneration != com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.generation ||
            snapshot.model != AssetManager.getModelType(context) || SupertonicTTS.isCancelled()
        try {
            val normalizer = TextNormalizer()
            val parts = MultiVoiceSettings.parts(context, prepared.text, prepared.voicePlan, AssetManager.voiceFile(context, snapshot.voice).path)
            sentenceLoop@ for ((part, style) in parts) for (sentence in normalizer.splitIntoSentences(part, "ru", preservePunctuation = true)) {
                if (obsolete()) break@sentenceLoop
                while (!SupertonicTTS.aheadCacheHasRoom()) {
                    if (obsolete()) return
                    SupertonicTTS.awaitAheadRoom()
                }
                val normalized = normalizer.normalize(sentence, "ru", skipStress = prepared.llmProcessed)
                if (obsolete()) break@sentenceLoop
                val pcm = SupertonicTTS.generateAudio(normalized, "ru", style, snapshot.rate, 0f, snapshot.steps, 2.5f,
                    preparationGeneration = snapshot.cacheGeneration, skipDictionary = prepared.llmProcessed, aheadOwner = owner)
                if (isDelivered(owner)) SupertonicTTS.releaseAheadCache(owner)
                if (pcm != null) android.util.Log.i("ReaderAhead", "Prepared ahead PCM chars=${normalized.length} bytes=${pcm.size} ${SupertonicTTS.audioCacheStatus()}")
            }
        } catch (t: Exception) { android.util.Log.w("ReaderAhead", "Role refresh synthesis failed error=${t.javaClass.simpleName}; foreground remains available") }
    }
    @Synchronized fun submit(ctx: Context, text: String, params: Bundle?) {
        val prefs=ctx.getSharedPreferences("SupertonicPrefs",0)
        if (!prefs.getBoolean("reader_early_prepare",true) || !AssetManager.isRussianModel(ctx) || text.length > 6000 || text.length < 12) return
        val packageNames=ctx.packageManager.getPackagesForUid(android.os.Binder.getCallingUid()).orEmpty()
        if (packageNames.any { it.contains("talkback") || it.contains("jieshuo") || it.contains("accessibility") }) return
        val owner = SpeechTextTrace.fingerprint(text)
        delivered.remove(owner)
        val requestedVoice=params?.getString("voiceName")?.substringAfter("-supertonic-","")?.takeIf { it.isNotEmpty() }?.plus(".json")
        // Same default as TextToSpeechService: without KEY_PARAM_RATE the framework uses the
        // system TTS rate, so a hard-coded 100 would make every prepared PCM a cache miss.
        val defaultRate=android.provider.Settings.Secure.getInt(ctx.contentResolver,android.provider.Settings.Secure.TTS_DEFAULT_RATE,100)
        val rate=(params?.takeIf { it.containsKey("rate") }?.getInt("rate",defaultRate) ?: defaultRate)/100f
        enqueue(ctx, text, requestedVoice, rate)
    }
    /** Settings changed mid-reading: redo the reader's undelivered queue with the new settings
     * instead of dropping it (each paragraph would otherwise wait for the LLM deadline and
     * synthesize on demand until the reader queues new text). Call after LLM invalidation. */
    @Synchronized fun invalidate(ctx: Context) {
        val pending = snapshots.filterKeys { it !in delivered }.values.map { it.text to (it.requestedVoice to it.rate) }
        cancel()
        if (pending.isEmpty() || !AssetManager.isRussianModel(ctx) ||
            !ctx.getSharedPreferences("SupertonicPrefs",0).getBoolean("reader_early_prepare",true)) return
        pending.forEach { (text, voiceRate) -> enqueue(ctx, text, voiceRate.first, voiceRate.second) }
        android.util.Log.i("ReaderAhead", "Requeued undelivered texts=${pending.size} after settings change")
    }
    @Synchronized private fun enqueue(ctx: Context, text: String, requestedVoice: String?, rate: Float) {
        val prefs=ctx.getSharedPreferences("SupertonicPrefs",0)
        val owner = SpeechTextTrace.fingerprint(text)
        val context=ctx.applicationContext;val generation=epoch.get();val model=AssetManager.getModelType(ctx)
        val textGeneration=preparedTexts.token()
        val cacheGeneration=com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.generation
        val voice=requestedVoice ?: prefs.getString("selected_voice","ru_f1.json")!!
        val steps=prefs.getInt("diffusion_steps",5)
        val snapshot = Snapshot(text, model, voice, rate.coerceIn(.5f, 2.5f), steps, generation, cacheGeneration, requestedVoice)
        snapshots[owner] = snapshot
        while (snapshots.size > 256) snapshots.remove(snapshots.keys.first())
        android.util.Log.i("ReaderAhead","Queued early text chars=${text.length} model=$model")
        val prepare: () -> Unit = work@{
            try {
                if(isDelivered(owner) || !isCurrent(owner, snapshot) || generation!=epoch.get() || !AssetManager.isReady(context) || model!=AssetManager.getModelType(context)) return@work
                // Background work has the duration of earlier playback available;
                // the foreground's short startup deadline is inappropriate here.
                val result=LlmPreparation.prepareResult(context,text,timeoutMs=30000,retainForPlayback=true)
                val prepared=result.text
                val llmProcessed=!result.fallback
                if(isDelivered(owner) || !isCurrent(owner, snapshot) || generation!=epoch.get() || model!=AssetManager.getModelType(context)) return@work
                preparedTexts.put(textGeneration,text,prepared,llmProcessed,result.voicePlan)
                synthesizeAhead(context, snapshot, PreparedSpeechText(prepared, llmProcessed, result.voicePlan))
            } catch(t: Throwable) { android.util.Log.w("ReaderAhead","Ahead preparation failed; normal synthesis remains available",t) }
        }
        if(queuedChars.get()+text.length>192000) {
            android.util.Log.w("ReaderAhead","Lookahead text budget reached; foreground synthesis will handle request")
            return
        }
        queuedChars.addAndGet(text.length)
        val task=AheadTask(text.length,prepare)
        try { worker.execute(task) } catch(_: java.util.concurrent.RejectedExecutionException) {
            task.release()
            android.util.Log.w("ReaderAhead","Lookahead queue full; foreground synthesis will handle request")
        }
    }
}
