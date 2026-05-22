package com.brahmadeo.supertonic.tts.tflite

import com.brahmadeo.supertonic.tts.SupertonicTTS
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Production hybrid TTS backend: INT4 .tflite for duration_predictor and
 * text_encoder, INT8 .onnx for vector_estimator (5 diffusion steps),
 * FP32 .onnx for vocoder (TFLite INT4 vocoder has audible 7-11 kHz
 * resonances).
 *
 * Holds open sessions and a parsed unicode indexer for the lifetime of the
 * preset selection — re-init only happens when SupertonicTTS.release() is
 * called (model switch / delete + redownload).
 */
class HybridEngine(
    private val modelDir: File,
) : AutoCloseable {

    private companion object {
        const val FIXED_TEXT_LEN = 320  // baked into the .tflite dp / text_enc graphs
        const val SAMPLE_RATE = 44100
    }

    private val onnxDir = File(modelDir, "onnx")
    private val tokenizer = UnicodeTokenizer(File(onnxDir, "unicode_indexer.json"))

    private val dp = Interpreter(File(onnxDir, "duration_predictor.tflite"), Interpreter.Options().setNumThreads(6).setUseXNNPACK(true))
    private val txtEnc = Interpreter(File(onnxDir, "text_encoder.tflite"), Interpreter.Options().setNumThreads(6).setUseXNNPACK(true))
    private val ve = OrtVectorEstimator(File(onnxDir, "vector_estimator.onnx"))
    private val voc = OrtVocoder(File(onnxDir, "vocoder.onnx"))

    private val voiceCache = HashMap<String, VoiceStyle>()

    /**
     * Synthesize one sentence. Result is the same little-endian 16-bit
     * signed mono PCM at 44.1 kHz that the Rust path produces, so the
     * PlaybackService channel doesn't need to care which engine made it.
     */
    fun synthesize(
        text: String,
        lang: String,
        stylePath: String,
        speed: Float,
        steps: Int,
        gain: Float,
        listener: SupertonicTTS.ProgressListener?,
        sessionId: Long,
    ): ByteArray {
        val voice = loadVoice(stylePath)
        val tok = tokenizer.tokenize(text, lang, FIXED_TEXT_LEN)

        // duration_predictor (TFLite INT4) -> scalar seconds
        val durBuf = directBuf(4)
        dp.runForMultipleInputsOutputs(
            arrayOf<Any>(
                directBuf(FIXED_TEXT_LEN * 8).also { it.asLongBuffer().put(tok.textIds); it.rewind() },
                directBuf(8 * 16 * 4).also { it.asFloatBuffer().put(voice.styleDp); it.rewind() },
                directBuf(FIXED_TEXT_LEN * 4).also { it.asFloatBuffer().put(tok.textMask); it.rewind() },
            ),
            mapOf(0 to durBuf),
        )
        durBuf.rewind()
        val durationSec = durBuf.asFloatBuffer().get(0) / speed.coerceAtLeast(0.1f)

        // text_encoder (TFLite INT4) -> [1, 256, 320] channels-first
        val textEmbOut = directBuf(256 * FIXED_TEXT_LEN * 4)
        txtEnc.runForMultipleInputsOutputs(
            arrayOf<Any>(
                directBuf(FIXED_TEXT_LEN * 8).also { it.asLongBuffer().put(tok.textIds); it.rewind() },
                directBuf(50 * 256 * 4).also { it.asFloatBuffer().put(voice.styleTtl); it.rewind() },
                directBuf(FIXED_TEXT_LEN * 4).also { it.asFloatBuffer().put(tok.textMask); it.rewind() },
            ),
            mapOf(0 to textEmbOut),
        )

        // Truncate text_emb [1, 256, 320] -> [1, 256, validLen] and the text
        // mask to validLen, so VE/vocoder only do work proportional to real
        // content. The TFLite text_encoder still pays for fixed 320 (its
        // graph is hard-coded), but VE × diffusion steps dwarfs that.
        val validLen = tok.validLen.coerceAtLeast(1)
        val textEmbFull = FloatArray(256 * FIXED_TEXT_LEN)
        textEmbOut.rewind()
        textEmbOut.asFloatBuffer().get(textEmbFull)
        val textEmb = FloatArray(256 * validLen)
        for (c in 0 until 256) {
            System.arraycopy(
                textEmbFull, c * FIXED_TEXT_LEN,
                textEmb, c * validLen,
                validLen,
            )
        }
        val textMask = FloatArray(validLen) { 1f }

        // sample noisy latent + mask sized exactly to the content
        val sample = LatentSampler.sample(durationSec)
        val latentLen = sample.latentLen

        // VE diffusion (ORT INT8) — all N steps in one call, constant
        // tensors marshalled to native memory once.
        val xt = ve.runDiffusion(
            initialLatent = sample.noisyLatent,
            textEmb = textEmb,
            styleTtl = voice.styleTtl,
            latentMask = sample.latentMask,
            textMask = textMask,
            textLen = validLen,
            latentLen = latentLen,
            totalSteps = steps,
            shouldCancel = { SupertonicTTS.isCancelled() },
        )
        if (SupertonicTTS.isCancelled()) return ByteArray(0)

        // vocoder (ORT FP32) — already dynamic latent_length
        val wavFull = voc.run(xt, latentLen)

        val keep = (durationSec * SAMPLE_RATE).toInt().coerceIn(0, wavFull.size)
        val pcm = floatPcmToBytes(wavFull, keep, gain)
        listener?.onAudioChunk(sessionId, pcm)
        return pcm
    }

    private fun loadVoice(stylePath: String): VoiceStyle {
        // Mixing isn't supported yet — take the first path if the caller
        // packed two voices via "path1;path2;alpha".
        val firstPath = stylePath.substringBefore(';')
        return voiceCache.getOrPut(firstPath) { VoiceStyle.load(File(firstPath)) }
    }

    private fun directBuf(bytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    private fun floatPcmToBytes(src: FloatArray, keep: Int, gain: Float): ByteArray {
        val out = ByteArray(keep * 2)
        for (i in 0 until keep) {
            val s = (src[i] * gain).coerceIn(-1f, 1f)
            val q = (s * 32767f).toInt()
            out[i * 2] = (q and 0xFF).toByte()
            out[i * 2 + 1] = ((q shr 8) and 0xFF).toByte()
        }
        return out
    }

    override fun close() {
        try { dp.close() } catch (_: Throwable) {}
        try { txtEnc.close() } catch (_: Throwable) {}
        try { ve.close() } catch (_: Throwable) {}
        try { voc.close() } catch (_: Throwable) {}
        voiceCache.clear()
    }
}
