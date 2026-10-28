package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** «Улучшать звук музыки»: the small music model sounds dull (on a guitar 99 % of the energy under ~1.6 kHz, «через
 * трубу»), the same at full precision, so it is the model. Once, when a track is made: a harmonic exciter (the upper mids
 * 2.5 kHz+ softly saturated, only the new harmonics above 5 kHz added), a +4 dB shelf above 4 kHz and a wider stereo
 * (side +3 dB). With the prompt hint: 99 % under 7.3 kHz, 16× more energy above 5 kHz. Off by default (own switch). */
object MusicEnhance {
    const val KEY = "music_enhance"
    /** Added to every music prompt: the model then makes clearer, brighter recordings (7× more above 5 kHz). */
    const val HINT = "high-fidelity stereo studio recording, crisp clear detailed highs, wide stereo"

    fun enabled(context: Context) = context.getSharedPreferences("SupertonicPrefs", 0).getBoolean(KEY, false)

    fun prompt(prompt: String) = if (prompt.contains("high-fidelity", true)) prompt else "${prompt.trimEnd('.', ' ')}, $HINT"

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        fun run(x: Double): Double { val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y; return y }
        companion object {
            fun highPass(f0: Double, rate: Int, q: Double = 0.707): Biquad {
                val w = 2 * PI * f0 / rate; val a = sin(w) / (2 * q); val c = cos(w); val n = 1 + a
                return Biquad((1 + c) / 2 / n, -(1 + c) / n, (1 + c) / 2 / n, -2 * c / n, (1 - a) / n)
            }
            fun highShelf(f0: Double, rate: Int, db: Double, s: Double = 0.8): Biquad {
                val a = 10.0.pow(db / 40); val w = 2 * PI * f0 / rate; val c = cos(w); val al = sin(w) / 2 * sqrt((a + 1 / a) * (1 / s - 1) + 2)
                val n = (a + 1) - (a - 1) * c + 2 * sqrt(a) * al
                return Biquad(a * ((a + 1) + (a - 1) * c + 2 * sqrt(a) * al) / n, -2 * a * ((a - 1) + (a + 1) * c) / n,
                    a * ((a + 1) + (a - 1) * c - 2 * sqrt(a) * al) / n, 2 * ((a - 1) - (a + 1) * c) / n, ((a + 1) - (a - 1) * c - 2 * sqrt(a) * al) / n)
            }
        }
    }

    /** In place on interleaved stereo [pcm]; the power stays the same (the loudness is normalised after). */
    fun apply(pcm: FloatArray, rate: Int, mix: Double = 0.35, drive: Double = 6.0, shelfDb: Double = 4.0, width: Double = 1.4) {
        val frames = pcm.size / 2
        var before = 0.0; for (v in pcm) before += v.toDouble() * v
        val out = DoubleArray(pcm.size)
        for (ch in 0..1) {
            val split = Biquad.highPass(2500.0, rate); val keep1 = Biquad.highPass(5000.0, rate); val keep2 = Biquad.highPass(5000.0, rate)
            val shelf = Biquad.highShelf(4000.0, rate, shelfDb)
            for (i in 0 until frames) {
                val x = pcm[i * 2 + ch].toDouble()
                val harmonics = keep2.run(keep1.run(tanh(drive * split.run(x)) / drive))
                out[i * 2 + ch] = shelf.run(x + mix * harmonics * drive / 2)
            }
        }
        var after = 0.0
        for (i in 0 until frames) {
            val m = (out[i * 2] + out[i * 2 + 1]) / 2; val s = (out[i * 2] - out[i * 2 + 1]) / 2 * width
            out[i * 2] = m + s; out[i * 2 + 1] = m - s
            after += out[i * 2] * out[i * 2] + out[i * 2 + 1] * out[i * 2 + 1]
        }
        val gain = if (after > 0) sqrt(before / after) else 1.0
        for (i in pcm.indices) pcm[i] = (out[i] * gain).toFloat()
    }
}
