package com.brahmadeo.supertonic.tts.tflite

import android.content.Context
import com.brahmadeo.supertonic.tts.ui.DebugLog
import com.brahmadeo.supertonic.tts.utils.AssetManager
import com.brahmadeo.supertonic.tts.utils.WavUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlin.system.measureTimeMillis

/**
 * TFLite + ORT hybrid synthesizer matching the upstream fork's recommended
 * "INT4 + INT8 VE" configuration:
 *   duration_predictor.tflite   (INT4)  - Kotlin via LiteRT
 *   text_encoder.tflite         (INT4)  - Kotlin via LiteRT
 *   vector_estimator.onnx       (INT8)  - Kotlin via onnxruntime-android Java API
 *   vocoder.tflite              (INT4)  - Kotlin via LiteRT
 *
 * Text length is padded to 320 to match the .tflite graphs' fixed input
 * shapes. Latent length is fixed at 320; vocoder output is truncated to
 * (duration_seconds * 44100) before being saved.
 */
object HybridPipeline {

    private const val FIXED_TEXT_LEN = 320
    private const val SAMPLE_RATE = 44100
    private const val REZA_INT4_BASE =
        "https://huggingface.co/Reza2kn/supertonic-3-litert/resolve/main/int4"
    private const val LANG = "ru"
    private const val VOICE = "F1"
    private const val TOTAL_STEPS = 5

    fun runRussianSynthesis(context: Context, text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                doRun(context, text)
            } catch (t: Throwable) {
                DebugLog.e("Hybrid crashed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun doRun(context: Context, text: String) {
        val tfliteDir = File(context.cacheDir, "tflite_smoke").apply { mkdirs() }
        ensureTfliteAssets(tfliteDir)

        val modelDir = File(context.filesDir, AssetManager.MODEL_VERSION)
        val onnxDir = File(modelDir, "onnx")
        val voiceFile = File(modelDir, "voice_styles/$VOICE.json")
        val indexerFile = File(onnxDir, "unicode_indexer.json")
        val veFile = File(onnxDir, "vector_estimator.onnx")
        for (f in listOf(voiceFile, indexerFile, veFile)) {
            require(f.exists()) { "Missing required asset: ${f.absolutePath}" }
        }

        DebugLog.i("Hybrid: tokenize+load (text='${text.take(40)}')")
        val tokenizer = UnicodeTokenizer(indexerFile)
        val voice = VoiceStyle.load(voiceFile)
        val tok = tokenizer.tokenize(text, LANG, FIXED_TEXT_LEN)
        DebugLog.i("  tokens=${tok.validLen}/$FIXED_TEXT_LEN")

        // Stage 1: TFLite duration predictor
        val durationSec: Float
        val dpMs = measureTimeMillis {
            TFLiteDurationPredictor(File(tfliteDir, "duration_predictor.tflite")).use { m ->
                durationSec = m.run(tok.textIds, voice.styleDp, tok.textMask)[0]
            }
        }
        DebugLog.i("  DP: %.3fs duration, %dms".format(durationSec, dpMs))

        // Stage 2: TFLite text encoder (output channels-first [1, 256, 320])
        val textEmbCF: FloatArray
        val txtEncMs = measureTimeMillis {
            TFLiteTextEncoder(File(tfliteDir, "text_encoder.tflite")).use { m ->
                textEmbCF = m.run(tok.textIds, voice.styleTtl, tok.textMask)
            }
        }
        // Transpose to channels-last [1, 320, 256] for VE
        val textEmbCL = transposeCFtoCL(textEmbCF, channels = 256, time = FIXED_TEXT_LEN)
        DebugLog.i("  TextEnc: ${textEmbCF.size} floats, $txtEncMs ms")

        // Stage 3: noisy latent + mask
        val sample = LatentSampler.sample(durationSec)
        DebugLog.i("  latent: len=${sample.latentLen} dim=144")

        // Stage 4: VE diffusion loop via ORT
        val latentMask3d = FloatArray(LatentSampler.FIXED_LATENT_LEN) { sample.latentMask[it] }
        var xt = sample.noisyLatent
        val veMs = measureTimeMillis {
            OrtVectorEstimator(veFile).use { ve ->
                for (step in 0 until TOTAL_STEPS) {
                    xt = ve.step(
                        noisyLatent = xt,
                        textEmb = textEmbCL,
                        styleTtl = voice.styleTtl,
                        latentMask = latentMask3d,
                        textMask = tok.textMask,
                        textLen = FIXED_TEXT_LEN,
                        currentStep = step,
                        totalStep = TOTAL_STEPS,
                    )
                }
            }
        }
        DebugLog.i("  VE: $TOTAL_STEPS steps in $veMs ms")

        // Stage 5: TFLite vocoder
        val wavFull: FloatArray
        val vocMs = measureTimeMillis {
            TFLiteVocoder(File(tfliteDir, "vocoder.tflite")).use { m ->
                wavFull = m.run(xt)
            }
        }
        val keep = (durationSec * SAMPLE_RATE).toInt().coerceIn(0, wavFull.size)
        DebugLog.i("  Vocoder: $vocMs ms, keeping $keep/${wavFull.size} samples")

        // Save WAV to cache
        val wavBytes = floatPcmToBytes(wavFull, keep)
        val out = File(context.cacheDir, "hybrid_${System.currentTimeMillis()}.wav")
        WavUtils.saveWav(out, wavBytes, SAMPLE_RATE)
        DebugLog.i("Hybrid done -> ${out.name} (${wavBytes.size / 1024} KB)")
    }

    private fun ensureTfliteAssets(dir: File) {
        for (name in listOf("duration_predictor.tflite", "text_encoder.tflite", "vocoder.tflite")) {
            val f = File(dir, name)
            if (!f.exists()) {
                DebugLog.i("Downloading $name...")
                URL("$REZA_INT4_BASE/$name").openStream().use { input ->
                    FileOutputStream(f).use { input.copyTo(it) }
                }
            }
        }
    }

    /** [channels, time] flat row-major -> [time, channels] flat row-major */
    private fun transposeCFtoCL(src: FloatArray, channels: Int, time: Int): FloatArray {
        require(src.size == channels * time)
        val out = FloatArray(channels * time)
        for (c in 0 until channels) {
            for (t in 0 until time) {
                out[t * channels + c] = src[c * time + t]
            }
        }
        return out
    }

    /** Float PCM [-1,1] -> little-endian 16-bit signed bytes, first [keep] samples. */
    private fun floatPcmToBytes(src: FloatArray, keep: Int): ByteArray {
        val out = ByteArray(keep * 2)
        for (i in 0 until keep) {
            val s = (src[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }
}
