package com.brahmadeo.supertonic.tts.tera

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.JsonReader
import com.brahmadeo.supertonic.tts.SupertonicTTS
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Random
import java.util.zip.GZIPInputStream
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Android port of the distilled TeraTTSv2 ONNX pipeline. */
class TeraEngine(private val root: File, context: Context,
                 val sampler: String = TeraQuality.FAST,
                 private val allowSpinning: Boolean = false,
                 val threads: Int = com.brahmadeo.supertonic.tts.utils.EngineThreads.selected(context)) : AutoCloseable {
    private val llmPrefs = context.applicationContext.getSharedPreferences("llm_settings", Context.MODE_PRIVATE)
    private val pausePrefs = context.applicationContext.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
    private val env = OrtEnvironment.getEnvironment()
    private val sessions = HashMap<String, OrtSession>()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        // Four sequential graphs otherwise keep separate pools spinning between runs.
        // Keep the parallel kernels and weights; let idle workers sleep.
        addConfigEntry("session.intra_op.allow_spinning", if (allowSpinning) "1" else "0")
        addConfigEntry("session.inter_op.allow_spinning", if (allowSpinning) "1" else "0")
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val indexer = JSONArray(File(root, "unicode_indexer.json").readText()).let { array ->
        IntArray(array.length()) { array.getInt(it) }
    }
    private val appContext = context.applicationContext
    // Optional NPU vocoder: fixed 16- and 84-frame windows (TeraVocoderChunks). The vocoder is
    // causal, so a shorter window is zero-padded at its end without changing earlier samples.
    private val npuVocoder = HashMap<Int, OrtSession>()
    val npuRequested = com.brahmadeo.supertonic.tts.utils.Npu.enabled(context)
    private var npuOff = !npuRequested
    private fun npuSession(frames: Int): OrtSession? {
        if (npuOff) return null
        val size = if (frames <= TeraVocoderChunks.FIRST) TeraVocoderChunks.FIRST else TeraVocoderChunks.NEXT + TeraVocoderChunks.CONTEXT
        if (frames > size) return null
        return npuVocoder[size] ?: try {
            com.brahmadeo.supertonic.tts.utils.Npu.session(appContext, env, File(root, "models/vocoder.onnx"),
                mapOf("batch" to 1L, "generated_latent_length" to size.toLong()), "tera-vocoder-$size").also { npuVocoder[size] = it }
        } catch (t: Throwable) {
            disableNpu("create: ${t.javaClass.simpleName}")
            null
        }
    }
    private fun disableNpu(reason: String) {
        npuOff = true
        npuVocoder.values.forEach { runCatching { it.close() } }; npuVocoder.clear()
        com.brahmadeo.supertonic.tts.utils.Npu.markFailed(appContext, "Tera vocoder $reason")
    }
    // Optional hybrid NPU sampler (TeraNpuSampler): compiled once in the background; until it is ready the
    // 8-step ONNX Loop runs on the CPU, afterwards that session is released.
    @Volatile private var npuSampler: TeraNpuSampler? = null
    /** The sampler stays on the CPU: on SM8850 even one FP16 NPU step audibly changes the sound (latent ≈20 dB, audio
     * ≈12 dB vs the CPU) for almost no gain; four steps 12–16 dB. Kept for SpeechDiagnostics teraNpuProbe only. */
    val npuSamplerSteps = 0
    @Volatile private var npuSamplerOff = !npuRequested || npuSamplerSteps == 0 ||
        !com.brahmadeo.supertonic.tts.utils.Npu.enabled(context, com.brahmadeo.supertonic.tts.utils.Npu.TERA_SAMPLER)
    @Volatile private var closed = false
    private val npuBuilder = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "TeraNpuBuild").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private fun disableNpuSampler(reason: String) {
        npuSamplerOff = true
        npuSampler?.let { runCatching { it.close() } }; npuSampler = null
        com.brahmadeo.supertonic.tts.utils.Npu.markFailed(appContext, "Tera sampler $reason", com.brahmadeo.supertonic.tts.utils.Npu.TERA_SAMPLER)
    }
    private val styles = HashMap<String, Pair<FloatArray, FloatArray>>()
    private val accents = TeraStressLookup(root)
    private val yoWords by lazy { readDictionary("yo_words.json.gz") }
    private val ambiguousStress = context.assets.open("tera_ambiguous_stress.txt")
        .bufferedReader(Charsets.UTF_8).use { it.readLines().toSet() }
    private val ambiguousYo = context.assets.open("tera_ambiguous_yo.txt")
        .bufferedReader(Charsets.UTF_8).use { it.readLines().toSet() }

    init {
        require(sampler in setOf("sampler_distilled_cfg3_8step", "sampler_teacher_8step"))
        require(threads in 1..16)
        require(indexer.size == 65536)
        android.util.Log.i("TeraTTS", "Loading sampler=$sampler threads=$threads")
        try {
            for (name in listOf("text_encoder", "duration_predictor", sampler, "vocoder")) {
                sessions[name] = env.createSession(File(root, "models/$name.onnx").absolutePath, options)
            }
            if (sampler == TeraQuality.FAST && !npuSamplerOff) npuBuilder.execute {
                val started = android.os.SystemClock.elapsedRealtime()
                try {
                    val built = TeraNpuSampler(appContext, File(root, "models"), threads, npuSamplerSteps)
                    if (closed || npuSamplerOff) built.close() else npuSampler = built
                    android.util.Log.i("TeraTTS", "NPU sampler ready ms=${android.os.SystemClock.elapsedRealtime() - started}")
                } catch (t: Throwable) { disableNpuSampler("build ${t.javaClass.simpleName}: ${t.message?.take(160)}") }
            }
        } catch (t: Throwable) {
            sessions.values.forEach { it.close() }
            options.close()
            accents.close()
            throw t
        }
    }

    private fun readDictionary(name: String): Map<String, String> {
        val result = HashMap<String, String>()
        JsonReader(InputStreamReader(GZIPInputStream(FileInputStream(File(root, "ruaccent/dictionary/$name"))), Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) result[reader.nextName()] = reader.nextString()
            reader.endObject()
        }
        return result
    }

    private fun accentText(text: String): String {
        val prepared = TeraTextPreparation.stress(text, accents::lookup, yoWords, ambiguousStress, ambiguousYo)
        return com.brahmadeo.supertonic.tts.utils.RussianYoPolicy.apply(text, prepared, llmPrefs.getBoolean("restore_yo",true))
    }

    private fun tokenize(text: String): LongArray {
        val ids = ArrayList<Long>(text.length)
        for (character in text) {
            val cp = character.code
            if (cp < indexer.size && indexer[cp] >= 0) ids.add(indexer[cp].toLong())
        }
        require(ids.isNotEmpty()) { "Text contains no TeraTTS tokens" }
        return ids.toLongArray()
    }

    private fun loadNpy(file: File, expected: Int): FloatArray {
        FileInputStream(file).use { stream ->
            val prefix = ByteArray(12)
            require(stream.read(prefix) == prefix.size && prefix[0] == 0x93.toByte()) { "Invalid NumPy style: $file" }
            val major = prefix[6].toInt()
            val headerLength = if (major <= 1) {
                (prefix[8].toInt() and 255) or ((prefix[9].toInt() and 255) shl 8)
            } else ByteBuffer.wrap(prefix, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val headerBytes = if (major <= 1) prefix.copyOfRange(10, 12) else ByteArray(0)
            val remainder = ByteArray(headerLength - headerBytes.size)
            require(stream.read(remainder) == remainder.size)
            val header = String(headerBytes + remainder, Charsets.US_ASCII)
            require("f4" in header && "True" !in header) { "Expected little-endian float32 style: $file" }
            val bytes = stream.readBytes()
            require(bytes.size == expected * 4) { "Unexpected style size: $file" }
            val floats = FloatArray(expected)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            return floats
        }
    }

    private fun voice(path: String): Pair<FloatArray, FloatArray> {
        val voiceDir = File(path.substringBefore(';')).parentFile
        require(voiceDir.parentFile.name == "styles")
        return styles.getOrPut(voiceDir.name) {
            loadNpy(File(voiceDir, "style_dp.npy"), 128) to
                loadNpy(File(voiceDir, "style_ttl.npy"), 12800)
        }
    }

    private fun floatTensor(data: FloatArray, vararg shape: Long) =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    private fun longTensor(data: LongArray, vararg shape: Long) =
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    private fun run(name: String, inputs: Map<String, OnnxTensor>): Pair<FloatArray, LongArray> {
        try {
            // The CPU sampler is released once the NPU sampler runs; bring it back if needed.
            sessions.getOrPut(name) { env.createSession(File(root, "models/$name.onnx").absolutePath, options) }.run(inputs).use { result ->
                val output = result[0] as OnnxTensor
                val shape = output.info.shape
                val data = FloatArray(shape.fold(1L) { a, b -> a * b }.toInt())
                output.floatBuffer.get(data)
                return data to shape
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    @Synchronized
    fun synthesize(text: String, lang: String, stylePath: String, speed: Float, gain: Float,
                   listener: SupertonicTTS.ProgressListener?, sessionId: Long, skipDictionary: Boolean = false): ByteArray {
        if (!pausePrefs.getBoolean("tera_punctuation_pauses", true)) return synthesizePart(text, lang, stylePath, speed, gain, listener, sessionId, skipDictionary)
        val parts = TeraPunctuationPauses.split(com.brahmadeo.supertonic.tts.utils.BookTextSpacing.normalize(text),
            pausePrefs.getInt("tera_comma_pause_ms", 180).coerceIn(80, 400),
            pausePrefs.getInt("tera_sentence_pause_ms", 420).coerceIn(0, 900))
        val output = java.io.ByteArrayOutputStream()
        for (part in TeraPunctuationPauses.synthesisParts(parts)) {
            if (SupertonicTTS.isCancelled()) return ByteArray(0)
            val pcm = synthesizePart(part.text, lang, stylePath, speed, gain, listener, sessionId, skipDictionary)
            if (pcm.isEmpty() || SupertonicTTS.isCancelled()) return ByteArray(0)
            output.write(pcm)
            val missing = TeraPunctuationPauses.missingSilenceSamples(pcm, part.pauseMs)
            if (missing > 0) {
                val silence = ByteArray(missing * 2)
                output.write(silence)
                listener?.onAudioChunk(sessionId, silence)
            }
        }
        return output.toByteArray()
    }

    private fun synthesizePart(text: String, lang: String, stylePath: String, speed: Float, gain: Float,
                   listener: SupertonicTTS.ProgressListener?, sessionId: Long, skipDictionary: Boolean = false): ByteArray {
        require(lang == "ru") { "TeraTTSv2 preset supports Russian only" }
        val (styleDp, styleTtl) = voice(stylePath)
        val punctuated = TeraTextPreparation.punctuation(text)
        val stressed = if (skipDictionary) TeraTextPreparation.explicitStress(punctuated) else accentText(punctuated)
        val prepared = Normalizer.normalize("<ru>${stressed}</ru>", Normalizer.Form.NFKD)
        val ids = tokenize(prepared)
        val durationIds = tokenize(prepared.replace("+", ""))
        val textLen = ids.size.toLong()
        val durationLen = durationIds.size.toLong()
        val textMask = FloatArray(ids.size) { 1f }
        val durationMask = FloatArray(durationIds.size) { 1f }
        val (embedding, embeddingShape) = run("text_encoder", mapOf(
            "text_ids" to longTensor(ids, 1, textLen),
            "style_ttl" to floatTensor(styleTtl, 1, 50, 256),
            "text_mask" to floatTensor(textMask, 1, 1, textLen)
        ))
        if (SupertonicTTS.isCancelled()) return ByteArray(0)
        val (duration, _) = run("duration_predictor", mapOf(
            "text_ids" to longTensor(durationIds, 1, durationLen),
            "style_dp" to floatTensor(styleDp, 1, 8, 16),
            "text_mask" to floatTensor(durationMask, 1, 1, durationLen)
        ))
        val natural = TeraDurationCap.seconds(duration[0] * TeraVoices.durationScale(stylePath) / 1.05f, punctuated,
            pausePrefs.getBoolean(TeraDurationCap.KEY, true))
        val seconds = natural / speed.coerceAtLeast(0.1f)
        require(seconds.isFinite() && seconds > 0f)
        val frames = ceil(seconds * 44100 / 3072).toInt().coerceAtLeast(1)
        val noise = FloatArray(144 * frames)
        val random = Random(1234)
        for (i in noise.indices) noise[i] = random.nextGaussian().toFloat()
        val samplerStarted = android.os.SystemClock.elapsedRealtime()
        val hybrid = npuSampler?.let { npuS ->
            try { npuS.sample(noise, frames, embedding, embeddingShape.last().toInt(), styleTtl)
                .also { sessions.remove(sampler)?.close() } }
            catch (t: Throwable) { disableNpuSampler("run ${t.javaClass.simpleName}: ${t.message?.take(160)}"); null }
        }
        if (hybrid != null) com.brahmadeo.supertonic.tts.utils.DiagLog.i("TeraTTS", "Sampler hybrid frames=$frames text=${embeddingShape.last()} ms=${android.os.SystemClock.elapsedRealtime() - samplerStarted} npuSteps=${npuSampler?.npuSteps} fallback=${npuSampler?.fallbackSteps}")
        val (latent, _) = if (hybrid != null) hybrid to longArrayOf(1, 144, frames.toLong()) else run(sampler, mapOf(
            "initial_latent" to floatTensor(noise, 1, 144, frames.toLong()),
            "text_emb" to floatTensor(embedding, *embeddingShape),
            "style_ttl" to floatTensor(styleTtl, 1, 50, 256),
            "latent_mask" to floatTensor(FloatArray(frames) { 1f }, 1, 1, frames.toLong()),
            "text_mask" to floatTensor(textMask, 1, 1, textLen),
            "guidance" to floatTensor(floatArrayOf(3f), 1)
        ))
        if (SupertonicTTS.isCancelled()) return ByteArray(0)
        val maxSamples = (seconds * 44100).roundToInt()
        val output = java.io.ByteArrayOutputStream(min(maxSamples * 2, 1_000_000))
        var emitted = 0
        val loudness = com.brahmadeo.supertonic.tts.utils.SpeechLoudness.Stream(gain,
            pausePrefs.getBoolean("voice_loudness_normalization", true))
        for ((contextStart, start, end) in TeraVocoderChunks.plan(frames)) {
            if (SupertonicTTS.isCancelled()) return ByteArray(0)
            val count = end - contextStart
            val slice = FloatArray(144 * count)
            for (channel in 0 until 144) {
                System.arraycopy(latent, channel * frames + contextStart, slice, channel * count, count)
            }
            val npu = npuSession(count)
            val (wave, _) = if (npu == null) run("vocoder", mapOf("latent" to floatTensor(slice, 1, 144, count.toLong()))) else {
                val size = if (count <= TeraVocoderChunks.FIRST) TeraVocoderChunks.FIRST else TeraVocoderChunks.NEXT + TeraVocoderChunks.CONTEXT
                val padded = FloatArray(144 * size)
                for (channel in 0 until 144) System.arraycopy(slice, channel * count, padded, channel * size, count)
                val input = floatTensor(padded, 1, 144, size.toLong())
                val result = try {
                    npu.run(mapOf("latent" to input)).use { result ->
                        val tensor = result[0] as OnnxTensor
                        val wave = FloatArray(tensor.info.shape.fold(1L) { a, b -> a * b }.toInt()).also { tensor.floatBuffer.get(it) }
                        require(wave.all { it.isFinite() }) { "non-finite NPU audio" }
                        wave to tensor.info.shape
                    }
                } catch (t: Throwable) {
                    // A failure during inference must not drop audio: disable the NPU, redo on CPU.
                    disableNpu("run: ${t.javaClass.simpleName}")
                    null
                } finally { input.close() }
                result ?: run("vocoder", mapOf("latent" to floatTensor(slice, 1, 144, count.toLong())))
            }
            val discard = (start - contextStart) * 3072
            val samples = min(min((end - start) * 3072, wave.size - discard), maxSamples - emitted)
            if (samples <= 0) break
            val pcm = loudness.pcm(wave.copyOfRange(discard, discard + samples))
            output.write(pcm)
            listener?.onAudioChunk(sessionId, pcm)
            emitted += samples
        }
        return output.toByteArray()
    }

    companion object { const val NPU_SAMPLER_STEPS = "tera_npu_sampler_steps" }

    override fun close() {
        closed = true; npuBuilder.shutdownNow()
        npuSampler?.let { runCatching { it.close() } }; npuSampler = null
        npuVocoder.values.forEach { runCatching { it.close() } }
        npuVocoder.clear()
        sessions.values.forEach { it.close() }
        sessions.clear()
        options.close()
        styles.clear()
        accents.close()
    }
}
