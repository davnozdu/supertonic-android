package com.brahmadeo.supertonic.tts.tflite

import com.brahmadeo.supertonic.tts.SupertonicTTS
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Production hybrid TTS backend: INT4 .tflite for duration_predictor and
 * text_encoder, INT8 .onnx for vector_estimator (5 diffusion steps),
 * FP32 .onnx for vocoder (TFLite INT4 vocoder has audible 7-11 kHz
 * resonances).
 *
 * Holds open sessions and a parsed unicode indexer for the lifetime of the
 * preset selection. Scratch direct ByteBuffers are pre-allocated for the
 * fixed-shape TFLite inputs/outputs so we don't allocateDirect ~325 KB of
 * off-heap memory on every synthesise call.
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

    // 4 threads per Interpreter (not 6): they run in parallel below, so the
    // combined budget targets ~8 active workers on 6-8 core phones rather
    // than oversubscribing with 2x6=12.
    private val dp = Interpreter(File(onnxDir, "duration_predictor.tflite"), Interpreter.Options().setNumThreads(4).setUseXNNPACK(true))
    private val txtEnc = Interpreter(File(onnxDir, "text_encoder.tflite"), Interpreter.Options().setNumThreads(4).setUseXNNPACK(true))
    private val ve = OrtVectorEstimator(File(onnxDir, "vector_estimator.onnx"))
    private val voc = OrtVocoder(File(onnxDir, "vocoder.onnx"))

    private val voiceCache = HashMap<String, VoiceStyle>()

    // Two-thread pool to run DP and text_encoder in parallel — they're
    // independent of each other and use separate Interpreter instances.
    private val parallel = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "HybridEngine-tflite").apply { isDaemon = true }
    }

    // Persistent direct ByteBuffers — allocateDirect off the call path.
    private val dpInIds = directBuf(FIXED_TEXT_LEN * 8)
    private val dpInStyle = directBuf(8 * 16 * 4)
    private val dpInMask = directBuf(FIXED_TEXT_LEN * 4)
    private val dpOut = directBuf(4)
    private val teInIds = directBuf(FIXED_TEXT_LEN * 8)
    private val teInStyle = directBuf(50 * 256 * 4)
    private val teInMask = directBuf(FIXED_TEXT_LEN * 4)
    private val teOut = directBuf(256 * FIXED_TEXT_LEN * 4)
    private val textEmbFull = FloatArray(256 * FIXED_TEXT_LEN)

    /**
     * Synthesize one sentence. Result is the same little-endian 16-bit
     * signed mono PCM at 44.1 kHz that the Rust path produces. Synchronised
     * because the scratch buffers are shared state.
     */
    @Synchronized
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

        // DP and text_encoder are mutually independent — run them on two
        // background threads so the wall time is max(dp, text_enc) instead
        // of dp + text_enc. Both pin their own scratch buffers so there's
        // no shared mutable state between them.
        dpInIds.rewind(); dpInIds.asLongBuffer().put(tok.textIds); dpInIds.rewind()
        dpInStyle.rewind(); dpInStyle.asFloatBuffer().put(voice.styleDp); dpInStyle.rewind()
        dpInMask.rewind(); dpInMask.asFloatBuffer().put(tok.textMask); dpInMask.rewind()
        dpOut.rewind()
        teInIds.rewind(); teInIds.asLongBuffer().put(tok.textIds); teInIds.rewind()
        teInStyle.rewind(); teInStyle.asFloatBuffer().put(voice.styleTtl); teInStyle.rewind()
        teInMask.rewind(); teInMask.asFloatBuffer().put(tok.textMask); teInMask.rewind()
        teOut.rewind()
        val dpFuture: Future<*> = parallel.submit {
            dp.runForMultipleInputsOutputs(
                arrayOf<Any>(dpInIds, dpInStyle, dpInMask),
                mapOf(0 to dpOut),
            )
        }
        val teFuture: Future<*> = parallel.submit {
            txtEnc.runForMultipleInputsOutputs(
                arrayOf<Any>(teInIds, teInStyle, teInMask),
                mapOf(0 to teOut),
            )
        }
        dpFuture.get()
        teFuture.get()

        dpOut.rewind()
        val durationSec = dpOut.asFloatBuffer().get(0) / speed.coerceAtLeast(0.1f)

        // Crop text_emb [1, 256, 320] -> [1, 256, validLen] channels-first.
        val validLen = tok.validLen.coerceAtLeast(1)
        teOut.rewind()
        teOut.asFloatBuffer().get(textEmbFull)
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
        try { parallel.shutdownNow() } catch (_: Throwable) {}
        try { dp.close() } catch (_: Throwable) {}
        try { txtEnc.close() } catch (_: Throwable) {}
        try { ve.close() } catch (_: Throwable) {}
        try { voc.close() } catch (_: Throwable) {}
        voiceCache.clear()
    }
}
