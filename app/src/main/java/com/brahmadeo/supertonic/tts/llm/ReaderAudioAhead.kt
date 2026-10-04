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
    private val worker = ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,ArrayBlockingQueue(16),
        { Thread(it,"ReaderAudioAhead").apply { isDaemon = true } },ThreadPoolExecutor.DiscardPolicy())
    // Pocket synthesis needs a larger rolling window; retain the existing window for other engines.
    private val pocketWorker=ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,ArrayBlockingQueue(128),
        { Thread(it,"PocketReaderAhead").apply { isDaemon=true } },ThreadPoolExecutor.AbortPolicy())
    private val pocketChars=java.util.concurrent.atomic.AtomicInteger()
    private class PocketTask(val chars: Int,val work: () -> Unit): Runnable {
        private val released=java.util.concurrent.atomic.AtomicBoolean()
        fun release() { if(released.compareAndSet(false,true)) pocketChars.addAndGet(-chars) }
        override fun run() { try { work() } finally { release() } }
    }
    private val preparedTexts=PreparedSpeechHandoff()
    @Synchronized fun cancel() {
        epoch.incrementAndGet(); preparedTexts.clear(); worker.queue.clear()
        val waiting=ArrayList<Runnable>();pocketWorker.queue.drainTo(waiting)
        waiting.forEach { (it as? PocketTask)?.release() }
    }
    internal fun takePrepared(text: String): PreparedSpeechText? = preparedTexts.takePrepared(text)
    @Synchronized fun submit(ctx: Context, text: String, params: Bundle?) {
        val prefs=ctx.getSharedPreferences("SupertonicPrefs",0)
        if (!prefs.getBoolean("reader_early_prepare",true) || !AssetManager.isRussianModel(ctx) || text.length > 6000 || text.length < 12) return
        val packageNames=ctx.packageManager.getPackagesForUid(android.os.Binder.getCallingUid()).orEmpty()
        if (packageNames.any { it.contains("talkback") || it.contains("jieshuo") || it.contains("accessibility") }) return
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
                if(generation!=epoch.get() || !AssetManager.isReady(context) || model!=AssetManager.getModelType(context)) return@work
                // Background work has the duration of earlier playback available;
                // the foreground's short startup deadline is inappropriate here.
                val result=LlmPreparation.prepareResult(context,text,timeoutMs=30000)
                val prepared=result.text
                val llmProcessed=!result.fallback
                if(generation!=epoch.get() || model!=AssetManager.getModelType(context)) return@work
                preparedTexts.put(textGeneration,text,prepared,llmProcessed)
                val normalizer=TextNormalizer()
                for(sentence in normalizer.splitIntoSentences(prepared,"ru",preservePunctuation=true)) {
                    if(generation!=epoch.get() || SupertonicTTS.isCancelled() || model!=AssetManager.getModelType(context)) break
                    val normalized=normalizer.normalize(sentence,"ru",skipStress=llmProcessed)
                    if(generation!=epoch.get()) break
                    val pcm=SupertonicTTS.generateAudio(normalized,"ru",AssetManager.voiceFile(context,voice).path,rate.coerceIn(.5f,2.5f),0f,steps,2.5f,preparationGeneration=cacheGeneration,skipDictionary=llmProcessed)
                    if(pcm!=null) android.util.Log.i("ReaderAhead","Prepared ahead PCM chars=${normalized.length} bytes=${pcm.size}")
                }
            } catch(t: Throwable) { android.util.Log.w("ReaderAhead","Ahead preparation failed; normal synthesis remains available",t) }
        }
        if(model==AssetManager.POCKET_MODEL) {
            if(pocketChars.get()+text.length>96000) {
                android.util.Log.w("ReaderAhead","Pocket lookahead text budget reached; foreground synthesis will handle request")
                return
            }
            pocketChars.addAndGet(text.length)
            val task=PocketTask(text.length,prepare)
            try { pocketWorker.execute(task) } catch(_: java.util.concurrent.RejectedExecutionException) {
                task.release()
                android.util.Log.w("ReaderAhead","Pocket lookahead queue full; foreground synthesis will handle request")
            }
        } else worker.execute(prepare)
    }
}
