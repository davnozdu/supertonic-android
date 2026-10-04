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
                 val sampler: String = TeraQuality.FAST) : AutoCloseable {
    private val llmPrefs = context.applicationContext.getSharedPreferences("llm_settings", Context.MODE_PRIVATE)
    private val pausePrefs = context.applicationContext.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
    private val env = OrtEnvironment.getEnvironment()
    private val sessions = HashMap<String, OrtSession>()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val indexer = JSONArray(File(root, "unicode_indexer.json").readText()).let { array ->
        IntArray(array.length()) { array.getInt(it) }
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
        require(indexer.size == 65536)
        try {
            for (name in listOf("text_encoder", "duration_predictor", sampler, "vocoder")) {
                sessions[name] = env.createSession(File(root, "models/$name.onnx").absolutePath, options)
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
            sessions.getValue(name).run(inputs).use { result ->
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
        val seconds = duration[0] * TeraVoices.durationScale(stylePath) / 1.05f / speed.coerceAtLeast(0.1f)
        require(seconds.isFinite() && seconds > 0f)
        val frames = ceil(seconds * 44100 / 3072).toInt().coerceAtLeast(1)
        val noise = FloatArray(144 * frames)
        val random = Random(1234)
        for (i in noise.indices) noise[i] = random.nextGaussian().toFloat()
        val (latent, _) = run(sampler, mapOf(
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
        for (start in 0 until frames step 16) {
            if (SupertonicTTS.isCancelled()) return ByteArray(0)
            val end = min(start + 16, frames)
            val contextStart = (start - 20).coerceAtLeast(0)
            val count = end - contextStart
            val slice = FloatArray(144 * count)
            for (channel in 0 until 144) {
                System.arraycopy(latent, channel * frames + contextStart, slice, channel * count, count)
            }
            val (wave, _) = run("vocoder", mapOf("latent" to floatTensor(slice, 1, 144, count.toLong())))
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

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        options.close()
        styles.clear()
        accents.close()
    }
}
