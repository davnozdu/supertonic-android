package com.brahmadeo.supertonic.tts.tera

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
import java.util.Locale
import java.util.Random
import java.util.zip.GZIPInputStream
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** Android port of the distilled TeraTTSv2 ONNX pipeline. */
class TeraEngine(private val root: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val sessions = HashMap<String, OrtSession>()
    private val indexer = JSONArray(File(root, "unicode_indexer.json").readText()).let { array ->
        IntArray(array.length()) { array.getInt(it) }
    }
    private val styles = HashMap<String, Pair<FloatArray, FloatArray>>()
    private val accents = TeraStressLookup(root)
    private val yoWords by lazy { readDictionary("yo_words.json.gz") }
    private val wordPattern = Regex("[+А-Яа-яЁё]+")

    init {
        require(indexer.size == 65536)
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        for (name in listOf("text_encoder", "duration_predictor", "sampler_distilled_cfg3_8step", "vocoder")) {
            sessions[name] = env.createSession(File(root, "models/$name.onnx").absolutePath, options)
        }
        options.close()
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
        // Lexicon entries use U+0301 after the vowel; Tera uses '+' before it.
        val manual = Regex("([АЕЁИОУЫЭЮЯаеёиоуыэюя])\u0301").replace(text) { "+${it.groupValues[1]}" }
        return wordPattern.replace(manual) { match ->
            val original = match.value
            if ('+' in original) return@replace original
            val yo = yoWords[original.lowercase(Locale.ROOT)]?.let { replacement ->
                replacement.mapIndexed { i, c -> if (original.getOrNull(i)?.isUpperCase() == true) c.uppercaseChar() else c }.joinToString("")
            } ?: original
            val marked = accents.lookup(yo.lowercase(Locale.ROOT)) ?: return@replace yo
            if (marked.replace("+", "").length != yo.length) return@replace yo
            val out = StringBuilder()
            var index = 0
            for (c in marked) {
                if (c == '+') out.append('+') else out.append(yo[index++])
            }
            out.toString()
        }
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
                   listener: SupertonicTTS.ProgressListener?, sessionId: Long): ByteArray {
        require(lang == "ru") { "TeraTTSv2 preset supports Russian only" }
        val (styleDp, styleTtl) = voice(stylePath)
        val prepared = Normalizer.normalize("<ru>${accentText(text)}</ru>", Normalizer.Form.NFKD)
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
        val seconds = duration[0] / 1.05f / speed.coerceAtLeast(0.1f)
        require(seconds.isFinite() && seconds > 0f)
        val frames = ceil(seconds * 44100 / 3072).toInt().coerceAtLeast(1)
        val noise = FloatArray(144 * frames)
        val random = Random(1234)
        for (i in noise.indices) noise[i] = random.nextGaussian().toFloat()
        val (latent, _) = run("sampler_distilled_cfg3_8step", mapOf(
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
            val pcm = ByteArray(samples * 2)
            for (i in 0 until samples) {
                val value = (wave[discard + i] * gain).coerceIn(-1f, 1f)
                val sample = (value * 32767f).roundToInt()
                pcm[i * 2] = sample.toByte()
                pcm[i * 2 + 1] = (sample shr 8).toByte()
            }
            output.write(pcm)
            listener?.onAudioChunk(sessionId, pcm)
            emitted += samples
        }
        return output.toByteArray()
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        styles.clear()
        accents.close()
    }
}
