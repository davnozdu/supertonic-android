package com.brahmadeo.supertonic.tts.tflite

import android.content.Context
import com.brahmadeo.supertonic.tts.ui.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * One-shot verification that LiteRT can actually open the author's INT4
 * .tflite assets on this device and produce sane outputs from synthetic
 * inputs. Downloads to cacheDir on first run, then keeps them.
 */
object TFLiteSmokeTest {
    private const val REZA_BASE =
        "https://huggingface.co/Reza2kn/supertonic-3-litert/resolve/main/int4"

    private val FILES = listOf(
        "duration_predictor.tflite",
        "text_encoder.tflite",
        "vocoder.tflite",
    )

    fun run(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runBlocking(context)
            } catch (t: Throwable) {
                DebugLog.e("LiteRT test crashed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun runBlocking(context: Context) {
        val dir = File(context.cacheDir, "tflite_smoke").apply { mkdirs() }

        for (name in FILES) {
            val f = File(dir, name)
            if (!f.exists()) {
                DebugLog.i("Downloading $name...")
                val url = URL("$REZA_BASE/$name")
                url.openStream().use { input ->
                    FileOutputStream(f).use { output -> input.copyTo(output) }
                }
                val mb = f.length() / 1024.0 / 1024.0
                DebugLog.i("Downloaded $name: %.1f MB".format(mb))
            }
        }

        // duration_predictor
        runOne("duration_predictor") {
            TFLiteDurationPredictor(File(dir, "duration_predictor.tflite")).use { m ->
                val textIds = LongArray(320) { if (it < 4) (it + 5).toLong() else 0L }
                val styleDp = FloatArray(128) { Random.nextFloat() * 0.1f }
                val mask = FloatArray(320) { if (it < 4) 1f else 0f }
                val out = m.run(textIds, styleDp, mask)
                "duration_predictor OK out=%.4f".format(out[0])
            }
        }

        // text_encoder
        runOne("text_encoder") {
            TFLiteTextEncoder(File(dir, "text_encoder.tflite")).use { m ->
                val textIds = LongArray(320) { if (it < 4) (it + 5).toLong() else 0L }
                val styleTtl = FloatArray(50 * 256) { Random.nextFloat() * 0.1f }
                val mask = FloatArray(320) { if (it < 4) 1f else 0f }
                val out = m.run(textIds, styleTtl, mask)
                val rms = kotlin.math.sqrt(out.fold(0.0) { acc, x -> acc + x * x } / out.size).toFloat()
                "text_encoder OK len=${out.size} rms=%.4f".format(rms)
            }
        }

        // vocoder
        runOne("vocoder") {
            TFLiteVocoder(File(dir, "vocoder.tflite")).use { m ->
                val latent = FloatArray(144 * 320) { Random.nextFloat() * 0.1f - 0.05f }
                val wav = m.run(latent)
                val peak = wav.maxOf { kotlin.math.abs(it) }
                val rms = kotlin.math.sqrt(wav.fold(0.0) { acc, x -> acc + x * x } / wav.size).toFloat()
                "vocoder OK len=${wav.size} rms=%.4f peak=%.4f".format(rms, peak)
            }
        }

        DebugLog.i("LiteRT smoke test done")
    }

    private inline fun runOne(label: String, block: () -> String) {
        try {
            val msg: String
            val ms = measureTimeMillis { msg = block() }
            DebugLog.i("$msg  ${ms}ms")
        } catch (t: Throwable) {
            DebugLog.e("$label FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
