package com.brahmadeo.supertonic.tts

import android.content.Context
import android.util.Log
import com.brahmadeo.supertonic.tts.tflite.HybridEngine
import com.brahmadeo.supertonic.tts.tera.TeraEngine
import com.brahmadeo.supertonic.tts.utils.AssetManager
import java.io.File
import java.util.concurrent.atomic.AtomicReference

object SupertonicTTS {
    @Volatile
    private var nativePtr: Long = 0
    @Volatile
    private var currentModelPath: String? = null
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var hybridEngine: HybridEngine? = null
    @Volatile private var teraEngine: TeraEngine? = null
    @Volatile private var lastAudioUse = 0L
    private val idleScheduler = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "TeraIdle").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var teraIdleTask: java.util.concurrent.ScheduledFuture<*>? = null
    private const val TERA_IDLE_MS = 120_000L
    private fun touchAudio() { lastAudioUse = android.os.SystemClock.elapsedRealtime() }
    @Synchronized private fun scheduleTeraIdle() {
        teraIdleTask?.cancel(false)
        teraIdleTask = if (teraEngine == null) null else idleScheduler.schedule({
            synchronized(this) {
                teraIdleTask = null
                if (teraEngine != null) {
                    val remaining = TERA_IDLE_MS - (android.os.SystemClock.elapsedRealtime() - lastAudioUse)
                    if (remaining > 0) scheduleTeraIdle()
                    else {
                        teraEngine?.close(); teraEngine = null; prewarmed = false
                        Log.i("SupertonicTTS", "Tera unloaded after idle; prepared PCM retained")
                    }
                }
            }
        }, TERA_IDLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    @Synchronized internal fun teraResident() = teraEngine != null
    private var sileroEngine: com.brahmadeo.supertonic.tts.silero.SileroEngine? = null
    private var pocketEngine: com.brahmadeo.supertonic.tts.pocket.PocketEngine? = null
    private var kokoroEngine: com.brahmadeo.supertonic.tts.kokoro.KokoroEngine? = null
    private val audioCache = com.brahmadeo.supertonic.tts.utils.SpeechAudioCache()
    private fun audioKey(context: Context, text: String, lang: String, style: String, speed: Float, steps: Int, gain: Float, skipDictionary: Boolean): String {
        val prefs=context.getSharedPreferences("SupertonicPrefs",0)
        // prefs.all copies the whole map; take one snapshot per key, not one per setting.
        val all=prefs.all
        val settings=listOf("voice_loudness_normalization","tera_teacher","tera_punctuation_pauses","tera_short_word_cap","tera_comma_pause_ms","tera_sentence_pause_ms","silero_intonation","silero_fixed_pauses","foreign_tts","foreign_engine","foreign_language").map { all[it] }
        val kokoroFull=AssetManager.isKokoro(context) && com.brahmadeo.supertonic.tts.kokoro.KokoroDownload.fullEnabled(context)
        return listOf(com.brahmadeo.supertonic.tts.utils.EngineThreads.selected(context),com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.generation,AssetManager.getModelType(context),kokoroFull,text,lang,style,speed,steps,gain,skipDictionary,settings).joinToString("\u0000")
    }
    @Synchronized private fun maybeKokoroEngine(context: Context): com.brahmadeo.supertonic.tts.kokoro.KokoroEngine {
        val full=com.brahmadeo.supertonic.tts.kokoro.KokoroDownload.fullEnabled(context)
        kokoroEngine?.let { if(it.fullPrecision==full && it.threads==com.brahmadeo.supertonic.tts.utils.EngineThreads.selected(context)) return it;it.close();kokoroEngine=null }
        return com.brahmadeo.supertonic.tts.kokoro.KokoroEngine(context,full).also { kokoroEngine=it }
    }
    @Synchronized private fun maybePocketEngine(context: Context): com.brahmadeo.supertonic.tts.pocket.PocketEngine {
        val threads = com.brahmadeo.supertonic.tts.utils.EngineThreads.selected(context)
        pocketEngine?.let { if (it.threads == threads) return it; it.close(); pocketEngine = null }
        return com.brahmadeo.supertonic.tts.pocket.PocketEngine(context, threads).also { pocketEngine = it }
    }
    @Synchronized private fun maybeSileroEngine(context: Context): com.brahmadeo.supertonic.tts.silero.SileroEngine {
        val threads = com.brahmadeo.supertonic.tts.utils.EngineThreads.selected(context)
        sileroEngine?.let { if (it.threads == threads && sileroModel == AssetManager.getModelType(context)) return it; it.close(); sileroEngine = null }
        sileroModel = AssetManager.getModelType(context)
        return com.brahmadeo.supertonic.tts.silero.SileroEngine(context, threads).also { sileroEngine = it }
    }
    private var sileroModel: String? = null
    fun clearAudioCache() { audioCache.clear() }
    private fun cacheLimitBytes(): Long =
        com.brahmadeo.supertonic.tts.utils.SpeechCacheBudget.limit(
            appContext?.getSharedPreferences("SupertonicPrefs",0)?.getInt("reader_pcm_cache_mb",256) ?: 256, Runtime.getRuntime().maxMemory())
    fun releaseAheadCache(owner: String) { audioCache.releaseAhead(owner) }
    fun aheadCacheHasRoom(): Boolean {
        val limit = cacheLimitBytes()
        return audioCache.status().aheadBytes < limit - minOf(16*1024L*1024L,limit/4)
    }
    fun audioCacheStatus(): String {
        val status = audioCache.status()
        return "retainedBytes=${status.retainedBytes} aheadBytes=${status.aheadBytes} limitBytes=${cacheLimitBytes()} entries=${status.entries}"
    }
    private fun cacheAudio(key: String, bytes: ByteArray?, preparedAhead: Boolean = false, aheadOwner: String? = null) {
        if(bytes==null || isCancelled()) return
        audioCache.put(key,bytes,cacheLimitBytes(),preparedAhead,aheadOwner)
    }
    private val foreignFallbackNormalizer by lazy { com.brahmadeo.supertonic.tts.utils.TextNormalizer() }

    /**
     * Hand the app context to SupertonicTTS once at startup so generateAudio
     * can route to the hybrid Kotlin engine for INT4 presets without
     * threading a Context through every caller (SupertonicTextToSpeechService,
     * PlaybackService, etc.).
     */
    fun setApplicationContext(context: Context) {
        appContext = context.applicationContext
        com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.initialize(context)
        com.brahmadeo.supertonic.tts.utils.TextNormalizer.context = context.applicationContext
        com.brahmadeo.supertonic.tts.utils.ReadingMemory.purgeLegacy(context.applicationContext)
    }

    @Synchronized
    private fun maybeHybridEngine(): HybridEngine? {
        val ctx = appContext ?: return null
        if (AssetManager.getModelType(ctx) != "android_optimized_int8") {
            // Other presets (Standard / FP32) run on the Rust path.
            hybridEngine?.let { it.close(); hybridEngine = null }
            return null
        }
        val threads = com.brahmadeo.supertonic.tts.utils.EngineThreads.selected(ctx)
        hybridEngine?.let { if (it.threads == threads) return it; it.close(); hybridEngine = null }
        val modelDir = File(ctx.filesDir, AssetManager.MODEL_VERSION)
        return try {
            HybridEngine(modelDir, threads).also { hybridEngine = it }
        } catch (t: Throwable) {
            Log.e("SupertonicTTS", "Failed to open HybridEngine: ${t.message}", t)
            null
        }
    }

    @Synchronized
    private fun maybeTeraEngine(): TeraEngine? {
        val ctx = appContext ?: return null
        if (!AssetManager.isTera(ctx)) {
            teraEngine?.close()
            teraEngine = null
            return null
        }
        val sampler=com.brahmadeo.supertonic.tts.tera.TeraQuality.selected(ctx)
        teraEngine?.let {
            if(it.sampler==sampler && it.threads==com.brahmadeo.supertonic.tts.utils.EngineThreads.selected(ctx)) return it
            it.close()
            teraEngine=null
        }
        return try {
            TeraEngine(File(ctx.filesDir, "${AssetManager.MODEL_VERSION}/tera"), ctx,sampler).also { teraEngine = it }
        } catch (t: Throwable) {
            Log.e("SupertonicTTS", "Failed to open TeraEngine", t)
            null
        }
    }

    init {
        try {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("supertonic_tts")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("SupertonicTTS", "Failed to load native library: ${e.message}")
        }
    }

    private var nativeThreads: Pair<Int, Int>? = null
    // Read once at init so callback.start() never waits for the model monitor.
    @Volatile private var nativeSampleRate = 44100
    private external fun init(modelPath: String, libPath: String, ortThreads: Int, xnnThreads: Int): Long
    private external fun synthesize(ptr: Long, text: String, lang: String, stylePath: String, speed: Float, bufferSeconds: Float, steps: Int, gain: Float): ByteArray
    private external fun getSocClass(ptr: Long): Int
    private external fun getSampleRate(ptr: Long): Int
    private external fun close(ptr: Long)
    private external fun reset(ptr: Long)

    @Synchronized
    fun isInitialized(modelPath: String): Boolean {
        if (nativePtr == 0L || currentModelPath != modelPath) return false
        return getSocClass(nativePtr) != -1 && (appContext?.let {
            nativeThreads == (com.brahmadeo.supertonic.tts.utils.EngineThreads.selected(it) to recommendedXnnThreads(it))
        } ?: true)
    }

    // XNNPACK executes the bulk of the ONNX graphs (Conv/MatMul) on the
    // Standard/FP32/FP16 presets, so its pool size — not ORT's — bounds
    // Rust-path synthesis speed. The old default of 1 left the heavy
    // vector_estimator/vocoder single-threaded while the hybrid Kotlin
    // path runs the same kernels with 6-thread XNNPACK (OrtVocoder /
    // OrtVectorEstimator). Match it, capped by the actual core count.
    private fun defaultXnnThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    /**
     * XNNPACK pool size for the active preset, or 0 to disable XNNPACK.
     *
     * The fp16 preset must run on plain CPU EP: XNNPACK is fp32-only and ORT
     * fails to build a session for fp16 graphs, which left the engine
     * uninitialised (synthesis silently produced nothing — a reader app just
     * scrolled through the text). The native side treats 0 as "no XNNPACK".
     */
    fun recommendedXnnThreads(context: Context): Int =
        if (AssetManager.getModelType(context) == "android_optimized_fp16") 0
        else com.brahmadeo.supertonic.tts.utils.EngineThreads.selected(context)

    @Synchronized
    fun initialize(modelPath: String, libPath: String, ortThreads: Int = appContext?.let { com.brahmadeo.supertonic.tts.utils.EngineThreads.selected(it) } ?: 4, xnnThreads: Int = appContext?.let { recommendedXnnThreads(it) } ?: defaultXnnThreads()): Boolean {
        if (nativePtr != 0L) {
            // Health check: Can we still talk to the engine?
            if (getSocClass(nativePtr) != -1) {
                if (currentModelPath == modelPath && nativeThreads == (ortThreads to xnnThreads)) {
                    Log.i("SupertonicTTS", "Engine already initialized and healthy for this path: $modelPath")
                    return true
                } else {
                    Log.i("SupertonicTTS", "Model path changed ($currentModelPath -> $modelPath). Re-initializing...")
                    release()
                }
            } else {
                Log.w("SupertonicTTS", "Engine pointer exists but is unhealthy. Re-initializing...")
                release()
            }
        }
        
        nativePtr = init(modelPath, libPath, ortThreads, xnnThreads)
        val success = nativePtr != 0L
        if (success) {
            currentModelPath = modelPath
            nativeThreads = ortThreads to xnnThreads
            nativeSampleRate = getSampleRate(nativePtr).takeIf { it > 0 } ?: 44100
            Log.i("SupertonicTTS", "Engine initialized successfully (ORT: $ortThreads, XNN: $xnnThreads) with model: $modelPath")
        } else {
            currentModelPath = null
            Log.e("SupertonicTTS", "Engine initialization FAILED")
        }
        return success
    }

    private var listeners = java.util.concurrent.CopyOnWriteArrayList<ProgressListener>()
    
    // VULN-003 fix: Use an atomic SessionContext to ensure sid and listener are updated together
    private class SessionContext(val sid: Long, val listener: ProgressListener?)
    private val currentSession = AtomicReference<SessionContext?>(null)

    interface ProgressListener {
        fun onProgress(sessionId: Long, current: Int, total: Int)
        fun onAudioChunk(sessionId: Long, data: ByteArray)
    }

    fun addProgressListener(listener: ProgressListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeProgressListener(listener: ProgressListener) {
        listeners.remove(listener)
    }

    // Called from JNI
    fun notifyProgress(current: Int, total: Int) {
        val ctx = currentSession.get()
        val sid = ctx?.sid ?: 0L
        val listener = ctx?.listener
        
        // Priority to task-specific listener
        if (listener != null) {
            listener.onProgress(sid, current, total)
        } else {
            // Only notify global listeners if no specific task listener is set
            for (l in listeners) l.onProgress(sid, current, total)
        }
    }

    // Called from JNI
    fun notifyAudioChunk(data: ByteArray) {
        val ctx = currentSession.get()
        val sid = ctx?.sid ?: 0L
        val listener = ctx?.listener
        
        // STRICT ISOLATION: Audio chunks ONLY go to the requester
        if (listener != null) {
            listener.onAudioChunk(sid, data)
        } else {
            // Only if no specific task listener is active (e.g. legacy app call)
            // we send to global listeners
            for (l in listeners) l.onAudioChunk(sid, data)
        }
    }

    @Volatile
    private var isCancelled = false

    fun setCancelled(cancelled: Boolean) {
        isCancelled = cancelled
    }

    // Called from JNI
    fun isCancelled(): Boolean {
        return isCancelled
    }

    private val sessionIdCounter = java.util.concurrent.atomic.AtomicLong()
    // Requests the listener is waiting for right now; reader look-ahead yields the model to them.
    private val foregroundWaiting = java.util.concurrent.atomic.AtomicInteger()

    /** Minimum audible pause at the end of a Kokoro/Shtorm chunk that ends a sentence, on every
     * reading path (Moon, built-in player, look-ahead, multi-voice parts), controlled by the
     * "audible punctuation pauses" switch; only missing silence is added. Tera and Silero keep
     * their own switches (Silero's fixed pauses are opt-in: the user found them choppy). */
    private fun sentencePauseMs(ctx: Context): Int {
        if (!AssetManager.isKokoro(ctx) && !AssetManager.isPocket(ctx)) return 0
        val prefs = ctx.getSharedPreferences("SupertonicPrefs", 0)
        if (!prefs.getBoolean("tera_punctuation_pauses", true)) return 0
        return prefs.getInt("tera_sentence_pause_ms", 420).coerceIn(0, 900)
    }

    fun generateAudio(text: String, lang: String, stylePath: String, speed: Float = 1.0f, bufferDuration: Float = 0.0f, steps: Int = 5, gain: Float = 1.0f, listener: ProgressListener? = null, preparationGeneration: Long? = null, skipDictionary: Boolean = false, aheadOwner: String? = null): ByteArray? {
        val pcm = generateAudioOne(text,lang,stylePath,speed,bufferDuration,steps,gain,listener,preparationGeneration,skipDictionary,aheadOwner)
        val pauseMs = appContext?.let { sentencePauseMs(it) } ?: 0
        // One inference per chunk: Kokoro pays ~0.5 s per call, so splitting sentences into
        // separate calls cost far more than it gained. Only the chunk end gets a pause.
        if (pcm == null || pauseMs <= 0 || isCancelled() || text.trimEnd('"', '\'', '»', '”', ')', ']', ' ').lastOrNull() !in listOf('.', '!', '?', '…')) return pcm
        val missing = com.brahmadeo.supertonic.tts.tera.TeraPunctuationPauses.missingSilenceSamples(pcm, pauseMs, getAudioSampleRate())
        if (missing <= 0) return pcm
        val silence = ByteArray(missing * 2)
        listener?.onAudioChunk(0L, silence)
        return pcm + silence
    }

    private fun generateAudioOne(text: String, lang: String, stylePath: String, speed: Float, bufferDuration: Float, steps: Int, gain: Float, listener: ProgressListener?, preparationGeneration: Long?, skipDictionary: Boolean, aheadOwner: String?): ByteArray? {
        if(preparationGeneration != null && preparationGeneration != com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.generation) return null
        touchAudio()
        val cacheKey=appContext?.let { audioKey(it,text,lang,stylePath,speed,steps,gain,skipDictionary) }
        if(cacheKey!=null) audioCache.get(cacheKey,consumeAhead=preparationGeneration==null,aheadOwner=aheadOwner)?.let { return deliverCached(text,it,listener) }
        val foreground=preparationGeneration==null
        // The monitor is unfair: a look-ahead loop could re-take it between its own sentences.
        if(foreground) foregroundWaiting.incrementAndGet()
        else while(foregroundWaiting.get()>0 && !isCancelled()) Thread.sleep(5)
        var counted=foreground
        val waiting=android.os.SystemClock.elapsedRealtime()
        try {
            return synchronized(this) {
                if(counted) { foregroundWaiting.decrementAndGet(); counted=false }
                val elapsed=android.os.SystemClock.elapsedRealtime()-waiting
                if(elapsed>100) Log.i("ReaderAhead","Uncached model lock wait=${elapsed}ms chars=${text.length} foreground=$foreground")
                generateAudioLocked(text,lang,stylePath,speed,bufferDuration,steps,gain,listener,preparationGeneration,skipDictionary,aheadOwner)
            }
        } finally { if(counted) foregroundWaiting.decrementAndGet() }
    }

    private fun deliverCached(text: String, hit: com.brahmadeo.supertonic.tts.utils.SpeechAudioCache.Hit, listener: ProgressListener?): ByteArray? {
        val cached=hit.pcm
        val sid=sessionIdCounter.incrementAndGet()
        Log.i("ReaderAhead","PCM cache hit chars=${text.length} bytes=${cached.size} entries=${hit.entries} retainedBytes=${hit.retainedBytes}")
        var offset=0
        while(offset<cached.size && !isCancelled()) {
            val end=minOf(offset+48000,cached.size)
            listener?.onAudioChunk(sid,cached.copyOfRange(offset,end)); offset=end
        }
        return if(isCancelled()) null else cached
    }

    /** Called only under the model monitor; cached delivery never enters it. */
    private fun generateAudioLocked(text: String, lang: String, stylePath: String, speed: Float, bufferDuration: Float, steps: Int, gain: Float, listener: ProgressListener?, preparationGeneration: Long?, skipDictionary: Boolean, aheadOwner: String?): ByteArray? {
        if(preparationGeneration != null && preparationGeneration != com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.generation) return null
        val cacheKey = appContext?.let { audioKey(it,text,lang,stylePath,speed,steps,gain,skipDictionary) }
        if(cacheKey!=null) audioCache.get(cacheKey,consumeAhead=preparationGeneration==null,aheadOwner=aheadOwner)?.let { return deliverCached(text,it,listener) }
        val sid = sessionIdCounter.incrementAndGet()
        currentSession.set(SessionContext(sid, listener))

        try {
            val context = appContext
            if (context != null && AssetManager.isRussianModel(context)) {
                val parts = if (lang in setOf("en", "cs", "eng", "ces", "cze") && !text.any { it in 'Ѐ'..'ӿ' })
                    listOf(com.brahmadeo.supertonic.tts.foreign.ForeignText.Part(text, true))
                    else com.brahmadeo.supertonic.tts.foreign.ForeignText.split(text)
                if (parts.any { it.foreign }) {
                    val output = java.io.ByteArrayOutputStream()
                    for (part in parts) {
                        if (isCancelled()) return null
                        if (part.text.isBlank()) continue
                        val foreign = if (part.foreign && com.brahmadeo.supertonic.tts.foreign.ForeignTts.enabled(context)) com.brahmadeo.supertonic.tts.foreign.ForeignTts.synthesize(context,
                            part.text, com.brahmadeo.supertonic.tts.foreign.ForeignTts.language(context, part.text, lang),
                            speed, getAudioSampleRate(), gain) else null
                        if (isCancelled()) return null
                        if (foreign != null) {
                            var position = 0
                            while (position < foreign.size && !isCancelled()) {
                                val end = minOf(position + 48000, foreign.size)
                                listener?.onAudioChunk(sid, foreign.copyOfRange(position, end)); position = end
                            }
                            output.write(foreign)
                        } else {
                            val russian = if (part.foreign) foreignFallbackNormalizer.normalize(
                                com.brahmadeo.supertonic.tts.foreign.ForeignText.transliterate(part.text), "ru") else part.text
                            val pcm = if (AssetManager.isKokoro(context)) {
                                val engine = maybeKokoroEngine(context)
                                engine.synthesize(russian, stylePath, speed, gain, listener, sid)
                            } else if (AssetManager.isPocket(context)) {
                                val engine=maybePocketEngine(context)
                                engine.synthesize(russian,stylePath,speed,gain,listener,sid)
                            } else if (AssetManager.isSilero(context)) {
                                val engine = maybeSileroEngine(context)
                                engine.synthesize(russian, stylePath, speed, gain, listener, sid)
                            } else {
                                val engine = maybeTeraEngine() ?: return null
                                engine.synthesize(russian, "ru", stylePath, speed, gain, listener, sid, skipDictionary)
                            }
                            if (pcm.isEmpty()) return null
                            output.write(pcm)
                        }
                    }
                    return output.toByteArray().also { if(cacheKey!=null) cacheAudio(cacheKey,it,preparationGeneration!=null,aheadOwner) }.takeIf { it.isNotEmpty() && !isCancelled() }
                }
            }
            if (appContext?.let { AssetManager.isKokoro(it) } == true) {
                val ctx = appContext!!
                val engine = maybeKokoroEngine(ctx)
                return engine.synthesize(text, stylePath, speed, gain, listener, sid).also { if (cacheKey != null) cacheAudio(cacheKey, it, preparationGeneration != null, aheadOwner) }.takeIf { it.isNotEmpty() }
            }
            if (appContext?.let { AssetManager.isPocket(it) } == true) {
                val ctx=appContext!!
                val engine=maybePocketEngine(ctx)
                return engine.synthesize(text,stylePath,speed,gain,listener,sid).also { if(cacheKey!=null) cacheAudio(cacheKey,it,preparationGeneration!=null,aheadOwner) }.takeIf { it.isNotEmpty() }
            }
            if (appContext?.let { AssetManager.isSilero(it) } == true) {
                val ctx = appContext!!
                val engine = maybeSileroEngine(ctx)
                return engine.synthesize(text, stylePath, speed, gain, listener, sid).also { if(cacheKey!=null) cacheAudio(cacheKey,it,preparationGeneration!=null,aheadOwner) }.takeIf { it.isNotEmpty() }
            }
            if (appContext?.let { AssetManager.isTera(it) } == true) {
                val engine = maybeTeraEngine() ?: return null
                return try {
                    engine.synthesize(text, lang, stylePath, speed, gain, listener, sid, skipDictionary).also { if(cacheKey!=null) cacheAudio(cacheKey,it,preparationGeneration!=null,aheadOwner) }.takeIf { it.isNotEmpty() }
                } catch (t: Throwable) {
                    Log.e("SupertonicTTS", "Tera synthesis failed", t)
                    null
                }
            }
            // Route to the hybrid Kotlin engine if the active preset is the
            // INT4 + INT8 VE bundle; the native Rust pipeline can't read
            // .tflite models.
            maybeHybridEngine()?.let { engine ->
                return try {
                    val data = engine.synthesize(text, lang, stylePath, speed, steps, gain, listener, sid)
                    if (data.isNotEmpty()) data else null
                } catch (e: Exception) {
                    Log.e("SupertonicTTS", "Hybrid synthesis exception: ${e.message}", e)
                    null
                }
            }

            if (nativePtr == 0L) {
                Log.e("SupertonicTTS", "Engine not initialized")
                return null
            }
            appContext?.let { ctx ->
                val threads = com.brahmadeo.supertonic.tts.utils.EngineThreads.selected(ctx)
                if (nativeThreads != (threads to recommendedXnnThreads(ctx))) {
                    val model = currentModelPath ?: return null
                    if (!initialize(model, ctx.applicationInfo.nativeLibraryDir + "/libonnxruntime.so")) return null
                }
            }
            val data = synthesize(nativePtr, text, lang, stylePath, speed, bufferDuration, steps, gain)
            return if (data.isNotEmpty()) data else null
        } catch (e: Exception) {
            Log.e("SupertonicTTS", "Native synthesis exception: ${e.message}")
            return null
        } finally {
            currentSession.set(null)
            touchAudio()
            scheduleTeraIdle()
        }
    }

    @Synchronized
    fun getSoC(): Int {
        if (nativePtr == 0L) return -1
        return getSocClass(nativePtr)
    }

    /** Lock-free: a cached reply must not wait while look-ahead synthesizes later text. */
    fun getAudioSampleRate(): Int {
        if (appContext?.let { AssetManager.isKokoro(it) } == true) return 24000
        if (appContext?.let { AssetManager.isPocket(it) } == true) return 24000
        if (appContext?.let { AssetManager.isSilero(it) } == true) return 48000
        if (appContext?.let { AssetManager.isTera(it) } == true) return 44100
        if (nativePtr == 0L) return 44100
        return nativeSampleRate
    }

    // True once the engine has run one full synthesize() pass since process
    // start. That first pass is where XNNPACK JITs its kernels for the current
    // SoC's instruction set and where ORT lays out activation buffers. Both
    // costs are paid only once per process; subsequent calls inherit the
    // warmed state. We track this so the two services don't double-prewarm.
    @Volatile
    private var prewarmed: Boolean = false

    /**
     * Run a throwaway synthesis to warm ORT graph / XNNPACK kernels.
     *
     * Best-effort and idempotent — silently no-ops if the engine isn't
     * initialised, the voice file is missing, or a prewarm has already
     * happened. Designed to be called once per service onCreate.
     *
     * Heavy: blocks the calling thread for ~500ms-1.5s on weak SoCs. Call
     * from a background thread, not from onCreate's main-thread context.
     */
    fun prewarm(stylePath: String) {
        if (prewarmed) return
        // For the hybrid INT4 preset the Rust nativePtr is intentionally 0 —
        // gate on either path being ready so XNNPACK kernel compilation runs
        // once at startup instead of on the user's first sentence.
        if (appContext?.let { AssetManager.isSilero(it) } != true && nativePtr == 0L && maybeHybridEngine() == null && maybeTeraEngine() == null) {
            Log.w("SupertonicTTS", "prewarm skipped: engine not ready")
            return
        }
        if (!java.io.File(stylePath).exists()) {
            Log.w("SupertonicTTS", "prewarm skipped: missing voice style $stylePath")
            return
        }
        // Use lang="en" + steps=3 for fastest possible warmup pass — the goal
        // is to compile kernels, not to produce useful audio. Result is
        // discarded by passing through generateAudio's regular path which
        // returns the PCM as a ByteArray we drop on the floor.
        try {
            val t0 = System.currentTimeMillis()
            generateAudio(
                text = ".",
                lang = if (appContext?.let { AssetManager.isRussianModel(it) } == true) "ru" else "en",
                stylePath = stylePath,
                speed = 1.0f,
                bufferDuration = 0.0f,
                steps = 3,
                gain = 1.0f,
                listener = null
            )
            val dt = System.currentTimeMillis() - t0
            prewarmed = true
            Log.i("SupertonicTTS", "Prewarm complete in ${dt}ms")
        } catch (e: Throwable) {
            Log.w("SupertonicTTS", "Prewarm failed (ignored)", e)
        }
    }

    @Synchronized
    fun release() {
        teraIdleTask?.cancel(false); teraIdleTask = null
        com.brahmadeo.supertonic.tts.llm.ReaderAudioAhead.cancel()
        com.brahmadeo.supertonic.tts.foreign.ForeignTts.reset()
        pocketEngine?.close()
        pocketEngine=null
        kokoroEngine?.close()
        kokoroEngine=null
        sileroEngine?.close()
        sileroEngine = null
        prewarmed = false
        teraEngine?.let {
            try { it.close() } catch (t: Throwable) { Log.w("SupertonicTTS", "TeraEngine close failed", t) }
            teraEngine = null
        }
        hybridEngine?.let {
            try { it.close() } catch (t: Throwable) { Log.w("SupertonicTTS", "HybridEngine close failed", t) }
            hybridEngine = null
        }
        if (nativePtr != 0L) {
            Log.i("SupertonicTTS", "Releasing engine: $nativePtr")
            close(nativePtr)
            nativePtr = 0
        }
    }

    /** After a model switch the previous engine would stay resident until its idle timer
     * (up to 2 min), so two large models could share RAM. Keep only the active one. */
    @Synchronized
    fun releaseInactive() {
        val ctx = appContext ?: return
        if (!AssetManager.isKokoro(ctx)) { kokoroEngine?.close(); kokoroEngine = null }
        if (!AssetManager.isPocket(ctx)) { pocketEngine?.close(); pocketEngine = null }
        if (!AssetManager.isSilero(ctx)) { sileroEngine?.close(); sileroEngine = null }
        if (!AssetManager.isTera(ctx)) {
            teraIdleTask?.cancel(false); teraIdleTask = null
            teraEngine?.let { runCatching { it.close() }; teraEngine = null; prewarmed = false }
        }
        if (AssetManager.getModelType(ctx) != "android_optimized_int8") hybridEngine?.let { runCatching { it.close() }; hybridEngine = null }
        Log.i("SupertonicTTS", "Inactive engines released for model=${AssetManager.getModelType(ctx)}")
    }

    @Synchronized
    fun reset() {
        if (nativePtr != 0L) {
            reset(nativePtr)
        }
    }
}
