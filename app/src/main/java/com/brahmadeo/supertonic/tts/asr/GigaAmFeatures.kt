package com.brahmadeo.supertonic.tts.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** GigaAM-v3's input, as its FeatureExtractor makes it (torchaudio MelSpectrogram: 16 kHz, n_fft = win = 320, hop 160,
 * periodic Hann, power 2, 64 HTK mel bands without norm, no centring; then log of the value clamped to 1e-9…1e9), and its
 * greedy CTC decoding. Pure: checked against the Python reference (numpy + onnxruntime) on the same audio. */
object GigaAmFeatures {
    const val SAMPLE_RATE = 16000
    const val MELS = 64
    private const val FFT = 320
    private const val HOP = 160
    private const val BINS = FFT / 2 + 1

    private val window = FloatArray(FFT) { (0.5 - 0.5 * cos(2 * PI * it / FFT)).toFloat() }
    private val cosTable = FloatArray(BINS * FFT) { val k = it / FFT; val n = it % FFT; cos(2 * PI * k * n / FFT).toFloat() }
    private val sinTable = FloatArray(BINS * FFT) { val k = it / FFT; val n = it % FFT; sin(2 * PI * k * n / FFT).toFloat() }
    /** [bin][mel] triangular HTK filters. */
    private val filters: Array<FloatArray> = run {
        fun hzToMel(f: Double) = 2595.0 * log10(1 + f / 700)
        fun melToHz(m: Double) = 700.0 * (10.0.pow(m / 2595) - 1)
        val top = hzToMel(SAMPLE_RATE / 2.0)
        val edges = DoubleArray(MELS + 2) { melToHz(top * it / (MELS + 1)) }
        Array(BINS) { b ->
            val f = b * (SAMPLE_RATE / 2.0) / (BINS - 1)
            FloatArray(MELS) { m ->
                val up = (f - edges[m]) / (edges[m + 1] - edges[m])
                val down = (edges[m + 2] - f) / (edges[m + 2] - edges[m + 1])
                max(0.0, min(up, down)).toFloat()
            }
        }
    }

    /** Frames of [samples] (−1…1, 16 kHz). */
    fun frames(samples: Int) = if (samples < FFT) 0 else 1 + (samples - FFT) / HOP

    /** Log-mel features, [MELS] rows of [frames] values each (the model's [1, 64, T] layout). */
    fun compute(samples: FloatArray): FloatArray {
        val t = frames(samples.size)
        val out = FloatArray(MELS * t)
        val frame = FloatArray(FFT)
        val power = FloatArray(BINS)
        for (i in 0 until t) {
            val start = i * HOP
            for (n in 0 until FFT) frame[n] = samples[start + n] * window[n]
            for (k in 0 until BINS) {
                var re = 0f; var im = 0f
                val base = k * FFT
                for (n in 0 until FFT) { val v = frame[n]; re += v * cosTable[base + n]; im -= v * sinTable[base + n] }
                power[k] = re * re + im * im
            }
            for (m in 0 until MELS) {
                var e = 0f
                for (k in 0 until BINS) e += power[k] * filters[k][m]
                out[m * t + i] = ln(e.coerceIn(1e-9f, 1e9f))
            }
        }
        return out
    }

    /** Greedy CTC: the best token of each step, repeats merged, blank (the last token) dropped; «▁» is a space. */
    fun decode(logProbs: FloatArray, steps: Int, vocab: List<String>): String {
        val classes = logProbs.size / steps
        val blank = classes - 1
        val out = StringBuilder()
        var previous = -1
        for (s in 0 until steps) {
            var best = 0; var bestValue = Float.NEGATIVE_INFINITY
            for (c in 0 until classes) { val v = logProbs[s * classes + c]; if (v > bestValue) { bestValue = v; best = c } }
            if (best != previous && best != blank) vocab.getOrNull(best)?.takeIf { it != "<unk>" }?.let { out.append(it) }
            previous = best
        }
        return out.toString().replace('▁', ' ').replace(Regex(" +"), " ").trim()
    }
}
