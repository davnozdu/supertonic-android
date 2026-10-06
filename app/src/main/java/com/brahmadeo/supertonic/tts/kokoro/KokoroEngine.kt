package com.brahmadeo.supertonic.tts.kokoro

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.brahmadeo.supertonic.tts.SupertonicTTS
import com.brahmadeo.supertonic.tts.utils.SpeechLoudness
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Ready upstream Q8 models, native acute-aware Russian G2P, 24 kHz PCM. */
class KokoroEngine(context: Context, val fullPrecision: Boolean = KokoroDownload.fullEnabled(context),
                   val threads: Int=com.brahmadeo.supertonic.tts.utils.EngineThreads.selected(context)) : AutoCloseable {
    private val root = KokoroDownload.root(context)
    private val prefs = context.applicationContext.getSharedPreferences("SupertonicPrefs", 0)
    private val env = OrtEnvironment.getEnvironment()
    private val sessions = mutableMapOf<String, OrtSession>()
    private val packs = mutableMapOf<String, FloatArray>()
    private val vocab = JSONObject(File(root, "config.json").readText()).getJSONObject("vocab").let { obj ->
        obj.keys().asSequence().associate { it.single() to obj.getInt(it) }
    }
    private var used = SystemClock.elapsedRealtime()
    private val appContext = context.applicationContext
    // NPU generator (full precision only). Graphs compile in the background; until a decoder is ready the
    // chunk runs on the CPU model, after that the CPU model is released.
    val npuRequested = fullPrecision && com.brahmadeo.supertonic.tts.utils.Npu.enabled(context, com.brahmadeo.supertonic.tts.utils.Npu.KOKORO)
    @Volatile private var npuOff = !npuRequested
    @Volatile private var closed = false
    private val npuDecoders = java.util.concurrent.ConcurrentHashMap<String, KokoroNpuDecoder>()
    private val npuBuilding = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val npuBuilder = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "KokoroNpuBuild").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val idle = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "KokoroIdle").apply { isDaemon = true } }
    init {
        require(KokoroDownload.supported())
        require(threads in 1..16)
        require(!fullPrecision || KokoroDownload.fullReady(context))
        check(KokoroPhonemizer.initialize(File(root, "espeak-data").path)) { "Не удалось открыть русские фонемы Kokoro" }
        idle.scheduleWithFixedDelay({ synchronized(this) { if ((sessions.isNotEmpty() || npuDecoders.isNotEmpty()) && SystemClock.elapsedRealtime() - used >= 120000) unload() } }, 15, 15, TimeUnit.SECONDS)
    }
    private fun modelKey(voice: String) = if(fullPrecision) { if(voice=="dima") "model_dima.onnx" else "model.onnx" }
                  else if (voice == "dima") "model_dima_quantized.onnx" else "model_quantized.onnx"
    private fun npuDecoder(voice: String): KokoroNpuDecoder? {
        if (npuOff) return null
        val key = modelKey(voice)
        npuDecoders[key]?.let { return it }
        // Compiled once: load now (~2 s) instead of keeping the 650 MB CPU model resident alongside.
        if (!npuBuilding.contains(key) && com.brahmadeo.supertonic.tts.utils.Npu.sharedCacheReady(appContext, "kokoro-${KokoroNpuDecoder.kitName(key)}" +
                KokoroNpuDecoder.chunkFrames(appContext).let { if (it == KokoroNpuDecoder.CHUNK_FRAMES) "" else "-c$it" })) {
            val started = SystemClock.elapsedRealtime()
            return try {
                KokoroNpuDecoder(appContext, root, key, threads).also { npuDecoders[key] = it
                    Log.i("KokoroTTS", "NPU decoder loaded model=$key ms=${SystemClock.elapsedRealtime() - started}") }
            } catch (t: Throwable) { disableNpu("load ${t.javaClass.simpleName}: ${t.message?.take(160)}"); null }
        }
        if (npuBuilding.add(key)) npuBuilder.execute {
            val started = SystemClock.elapsedRealtime()
            try {
                val decoder = KokoroNpuDecoder(appContext, root, key, threads)
                if (npuOff || closed) decoder.close() else npuDecoders[key] = decoder
                Log.i("KokoroTTS", "NPU decoder ready model=$key ms=${SystemClock.elapsedRealtime() - started}")
            } catch (t: Throwable) { disableNpu("build ${t.javaClass.simpleName}: ${t.message?.take(160)}") }
            finally { npuBuilding.remove(key) }
        }
        return null
    }
    private fun disableNpu(reason: String) {
        npuOff = true
        Log.w("KokoroTTS", "NPU off, CPU continues: $reason")
        com.brahmadeo.supertonic.tts.utils.Npu.markFailed(appContext, "Kokoro $reason", com.brahmadeo.supertonic.tts.utils.Npu.KOKORO)
        npuDecoders.values.forEach { runCatching { it.close() } }; npuDecoders.clear()
    }
    /** NPU waveform for one chunk, or null when the CPU model must run it. */
    private fun npuWave(voice: String, ids: LongArray, style: FloatArray, speed: Float): FloatArray? {
        val decoder = npuDecoder(voice) ?: return null
        return try {
            val pre = decoder.prepare(ids, style, speed)
            if (decoder.frames(pre) > KokoroNpuDecoder.MAX_FRAMES) null
            else decoder.generate(pre).also { wave ->
                require(wave.size in 1..2_400_000 && wave.all { it.isFinite() }) { "invalid NPU audio size=${wave.size}" }
                sessions.remove(modelKey(voice))?.close()
            }
        } catch (t: Throwable) { disableNpu("run ${t.javaClass.simpleName}: ${t.message?.take(160)}"); null }
    }
    private fun session(voice: String): OrtSession {
        val key = modelKey(voice)
        return sessions.getOrPut(key) {
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(threads)
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                options.addConfigEntry("session.inter_op.allow_spinning", "0")
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                env.createSession(File(root, key).path, options)
            }
        }
    }
    private fun pack(voice: String) = packs.getOrPut(voice) {
        val bytes = File(root, "$voice.bin").readBytes()
        require(bytes.size == 510 * 256 * 4)
        FloatArray(510 * 256).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }
    @Synchronized fun synthesize(text: String, voiceFile: String, speed: Float, gain: Float, listener: SupertonicTTS.ProgressListener?, sid: Long): ByteArray {
        used = SystemClock.elapsedRealtime()
        try {
            val started = used
            val voice = File(voiceFile).name.removeSuffix(".bin").removeSuffix(".json").takeIf { it in KokoroDownload.voices } ?: "sveta"
            val ipa = KokoroG2p.phonemize(text, KokoroPhonemizer::phonemes)
            val g2pMs=SystemClock.elapsedRealtime()-started
            var inferenceMs=0L;var loadMs=0L;var npuMs=0L;var npuChunks=0
            val unknown = ipa.filter { it !in vocab }.toSet()
            require(unknown.isEmpty()) { "Kokoro: неподдерживаемые фонемы ${unknown.map { it.code }}" }
            val output = ByteArrayOutputStream()
            for (chunk in KokoroG2p.chunks(ipa)) {
                if (SupertonicTTS.isCancelled()) return ByteArray(0)
                val ids = longArrayOf(0) + chunk.map { vocab.getValue(it).toLong() }.toLongArray() + longArrayOf(0)
                val style = pack(voice).copyOfRange((chunk.length - 1) * 256, chunk.length * 256)
                val npuStarted = SystemClock.elapsedRealtime()
                val npu = npuWave(voice, ids, style, speed.coerceIn(.5f, 2.5f))
                if (npu != null) { npuChunks++; npuMs += SystemClock.elapsedRealtime() - npuStarted } else if (npuRequested) npuMs += SystemClock.elapsedRealtime() - npuStarted
                val wave = npu ?: run {
                    val inputs = mapOf("input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())),
                        "style" to OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, 256)),
                        "speed" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed.coerceIn(.5f, 2.5f))), longArrayOf(1)))
                    try {
                        val loading=SystemClock.elapsedRealtime()
                        val model=session(voice)
                        loadMs+=SystemClock.elapsedRealtime()-loading
                        val inference=SystemClock.elapsedRealtime()
                        model.run(inputs).use { result ->
                            val tensor = result[0] as OnnxTensor
                            val count = tensor.info.shape.fold(1L) { a, b -> a * b }
                            require(count in 1..2_400_000 && output.size().toLong() + count * 2 <= 64L * 1024 * 1024) { "Kokoro: превышен лимит звука" }
                            FloatArray(count.toInt()).also { tensor.floatBuffer.get(it) }
                        }.also { inferenceMs+=SystemClock.elapsedRealtime()-inference }
                    } finally { inputs.values.forEach { it.close() } }
                }
                if (SupertonicTTS.isCancelled()) return ByteArray(0)
                require(wave.all { it.isFinite() }) { "Kokoro: некорректный звук" }
                val pcm = SpeechLoudness.pcm(wave, SpeechLoudness.scale(listOf(wave), gain, prefs.getBoolean("voice_loudness_normalization", true)))
                var position = 0
                while (position < pcm.size) {
                    if (SupertonicTTS.isCancelled()) return ByteArray(0)
                    val end = minOf(position + 24000, pcm.size)
                    val bytes = pcm.copyOfRange(position, end)
                    output.write(bytes); listener?.onAudioChunk(sid, bytes); position = end
                }
            }
            Log.i("KokoroTTS", "Synthesized chars=${text.length} phonemes=${ipa.length} voice=$voice full=$fullPrecision threads=$threads ms=${SystemClock.elapsedRealtime() - started} g2pMs=$g2pMs loadMs=$loadMs inferenceMs=$inferenceMs npuChunks=$npuChunks npuMs=$npuMs audioMs=${output.size() * 1000L / 48000}")
            return output.toByteArray()
        } finally { used = SystemClock.elapsedRealtime() }
    }
    private fun unload() { sessions.values.forEach { it.close() }; sessions.clear(); packs.clear()
        npuDecoders.values.forEach { runCatching { it.close() } }; npuDecoders.clear(); Log.i("KokoroTTS", "Models released after idle; PCM cache retained") }
    @Synchronized override fun close() { closed = true; idle.shutdownNow(); npuBuilder.shutdownNow(); unload() }
}
