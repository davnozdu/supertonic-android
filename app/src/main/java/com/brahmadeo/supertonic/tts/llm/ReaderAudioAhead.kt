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
    @Synchronized private fun isDelivered(owner: String) = owner in delivered
    @Synchronized fun cancel() {
        epoch.incrementAndGet(); preparedTexts.clear(); delivered.clear()
        val waiting=ArrayList<Runnable>();worker.queue.drainTo(waiting)
        waiting.forEach { (it as? AheadTask)?.release() }
    }
    @Synchronized internal fun takePrepared(text: String): PreparedSpeechText? {
        val prepared = preparedTexts.takePrepared(text)
        if (prepared != null) LlmPreparation.consumed(text)
        val owner = SpeechTextTrace.fingerprint(text)
        delivered.add(owner)
        while (delivered.size > 1024) delivered.remove(delivered.first())
        SupertonicTTS.releaseAheadCache(owner)
        return prepared
    }
    @Synchronized fun submit(ctx: Context, text: String, params: Bundle?) {
        val prefs=ctx.getSharedPreferences("SupertonicPrefs",0)
        if (!prefs.getBoolean("reader_early_prepare",true) || !AssetManager.isRussianModel(ctx) || text.length > 6000 || text.length < 12) return
        val packageNames=ctx.packageManager.getPackagesForUid(android.os.Binder.getCallingUid()).orEmpty()
        if (packageNames.any { it.contains("talkback") || it.contains("jieshuo") || it.contains("accessibility") }) return
        val owner = SpeechTextTrace.fingerprint(text)
        delivered.remove(owner)
        val context=ctx.applicationContext;val generation=epoch.get();val model=AssetManager.getModelType(ctx)
        val textGeneration=preparedTexts.token()
        val cacheGeneration=com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.generation
        val voice=params?.getString("voiceName")?.substringAfter("-supertonic-","")?.takeIf { it.isNotEmpty() }?.plus(".json")
            ?: prefs.getString("selected_voice","ru_f1.json")!!
        val rate=(params?.getInt("rate",100) ?: 100)/100f
        val steps=prefs.getInt("diffusion_steps",5)
        android.util.Log.i("ReaderAhead","Queued early text chars=${text.length} model=$model")
        val prepare: () -> Unit = work@{
            try {
                if(isDelivered(owner) || generation!=epoch.get() || !AssetManager.isReady(context) || model!=AssetManager.getModelType(context)) return@work
                // Background work has the duration of earlier playback available;
                // the foreground's short startup deadline is inappropriate here.
                val result=LlmPreparation.prepareResult(context,text,timeoutMs=30000,retainForPlayback=true)
                val prepared=result.text
                val llmProcessed=!result.fallback
                if(isDelivered(owner) || generation!=epoch.get() || model!=AssetManager.getModelType(context)) return@work
                preparedTexts.put(textGeneration,text,prepared,llmProcessed,result.voicePlan)
                val normalizer=TextNormalizer()
                val parts = MultiVoiceSettings.parts(context, prepared, result.voicePlan, AssetManager.voiceFile(context,voice).path)
                sentenceLoop@ for ((part, style) in parts) for(sentence in normalizer.splitIntoSentences(part,"ru",preservePunctuation=true)) {
                    if(isDelivered(owner) || generation!=epoch.get() || SupertonicTTS.isCancelled() || model!=AssetManager.getModelType(context)) break@sentenceLoop
                    while (!SupertonicTTS.aheadCacheHasRoom()) {
                        if(isDelivered(owner) || generation!=epoch.get() || SupertonicTTS.isCancelled()) return@work
                        Thread.sleep(200) // Background only; playback never waits for cache capacity.
                    }
                    val normalized=normalizer.normalize(sentence,"ru",skipStress=llmProcessed)
                    if(isDelivered(owner) || generation!=epoch.get()) break@sentenceLoop
                    val pcm=SupertonicTTS.generateAudio(normalized,"ru",style,rate.coerceIn(.5f,2.5f),0f,steps,2.5f,preparationGeneration=cacheGeneration,skipDictionary=llmProcessed,aheadOwner=owner)
                    if(isDelivered(owner)) SupertonicTTS.releaseAheadCache(owner)
                    if(pcm!=null) android.util.Log.i("ReaderAhead","Prepared ahead PCM chars=${normalized.length} bytes=${pcm.size} ${SupertonicTTS.audioCacheStatus()}")
                }
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
