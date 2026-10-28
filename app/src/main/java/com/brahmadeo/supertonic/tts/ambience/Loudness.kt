package com.brahmadeo.supertonic.tts.ambience

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Integrated loudness (ITU-R BS.1770-4: K-weighting, 400 ms blocks with 75 % overlap, absolute −70 LUFS and
 * relative −10 LU gates) and normalisation, so every location of every book sounds equally loud at one volume. */
object Loudness {
    const val TARGET_LUFS = -20.0
    private const val CEILING = 0.9f

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private fun kWeighting(rate: Int): List<Biquad> {
        // Pre-filter (high shelf) and RLB high-pass, coefficients derived for any sample rate (as pyloudnorm).
        val g = 3.99984385397; val q1 = 0.7071752369554193; val f1 = 1681.9744509555319
        val a = 10.0.pow(g / 40); val w1 = 2 * Math.PI * f1 / rate; val al1 = sin(w1) / (2 * q1); val c1 = cos(w1)
        val n0 = (a + 1) - (a - 1) * c1 + 2 * sqrt(a) * al1
        val shelf = Biquad(a * ((a + 1) + (a - 1) * c1 + 2 * sqrt(a) * al1) / n0, -2 * a * ((a - 1) + (a + 1) * c1) / n0,
            a * ((a + 1) + (a - 1) * c1 - 2 * sqrt(a) * al1) / n0, 2 * ((a - 1) - (a + 1) * c1) / n0, ((a + 1) - (a - 1) * c1 - 2 * sqrt(a) * al1) / n0)
        val q2 = 0.5003270373253953; val f2 = 38.13547087613982
        val w2 = 2 * Math.PI * f2 / rate; val al2 = sin(w2) / (2 * q2); val c2 = cos(w2); val n2 = 1 + al2
        val highPass = Biquad((1 + c2) / 2 / n2, -(1 + c2) / n2, (1 + c2) / 2 / n2, -2 * c2 / n2, (1 - al2) / n2)
        return listOf(shelf, highPass)
    }

    /** Loudness of interleaved stereo [pcm] in LUFS (−∞ for silence). */
    fun integrated(pcm: FloatArray, rate: Int): Double {
        val frames = pcm.size / 2
        val weighted = Array(2) { ch ->
            var x = DoubleArray(frames) { pcm[it * 2 + ch].toDouble() }
            for (f in kWeighting(rate)) {
                val y = DoubleArray(frames); var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
                for (i in 0 until frames) {
                    val v = f.b0 * x[i] + f.b1 * x1 + f.b2 * x2 - f.a1 * y1 - f.a2 * y2
                    x2 = x1; x1 = x[i]; y2 = y1; y1 = v; y[i] = v
                }
                x = y
            }
            x
        }
        val block = (0.4 * rate).toInt(); val step = block / 4
        if (frames < block) return Double.NEGATIVE_INFINITY
        val powers = ArrayList<Double>()
        var start = 0
        while (start + block <= frames) {
            var sum = 0.0
            for (ch in 0..1) { var s = 0.0; for (i in start until start + block) s += weighted[ch][i] * weighted[ch][i]; sum += s / block }
            powers += sum; start += step
        }
        fun lufs(p: Double) = -0.691 + 10 * log10(p)
        val absolute = powers.filter { lufs(it) > -70.0 }
        if (absolute.isEmpty()) return Double.NEGATIVE_INFINITY
        val relative = lufs(absolute.average()) - 10.0
        val gated = absolute.filter { lufs(it) > relative }
        return lufs(gated.ifEmpty { absolute }.average())
    }

    /** [pcm] brought to [target] LUFS in place; peaks above [CEILING] are softly limited (≤ 1.0). Returns the gain in dB. */
    fun normalize(pcm: FloatArray, rate: Int, target: Double = TARGET_LUFS): Double {
        val now = integrated(pcm, rate)
        if (now.isInfinite()) return 0.0
        val db = (target - now).coerceIn(-30.0, 30.0)
        val gain = 10.0.pow(db / 20).toFloat()
        for (i in pcm.indices) {
            val v = pcm[i] * gain; val m = abs(v)
            pcm[i] = if (m <= CEILING) v else Math.copySign(CEILING + (1 - CEILING) * tanh((m - CEILING) / (1 - CEILING)).toFloat(), v)
        }
        return db
    }
}
