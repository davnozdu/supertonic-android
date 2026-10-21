package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Optional clean-up of the synthesized speech, each filter with its own switch in «Обработка звука».
 * All of them are a few operations per sample (a minute of audio in ~25 ms on a desktop JVM, a fraction of a percent
 * of the synthesis work), streaming, with a 5 ms delay:
 * edge fades (clicks where a phrase starts or ends off zero), a 70 Hz high-pass (hum, DC offset), a soft limiter
 * instead of hard clipping, and a pause gate that lowers hiss between words (off by default: it can soften quiet
 * word endings). */
object SpeechFilters {
    const val FADE = "filter_edge_fade"
    const val HIGH_PASS = "filter_high_pass"
    const val LIMITER = "filter_soft_limiter"
    const val GATE = "filter_pause_gate"
    val keys = setOf(FADE, HIGH_PASS, LIMITER, GATE)
    private val defaults = mapOf(FADE to true, HIGH_PASS to true, LIMITER to true, GATE to false)
    fun enabled(ctx: Context, key: String) = ctx.applicationContext.getSharedPreferences("SupertonicPrefs", 0).getBoolean(key, defaults.getValue(key))

    /** Read by [SpeechLoudness.pcm], where the float samples are still unclipped; refreshed per request. */
    @Volatile var softLimit = true

    /** The stream stage for one request at the output [rate], or null when its filters are all off. */
    fun stream(ctx: Context, rate: Int): Stream? {
        softLimit = enabled(ctx, LIMITER)
        val fade = enabled(ctx, FADE); val highPass = enabled(ctx, HIGH_PASS); val gate = enabled(ctx, GATE)
        return if (fade || highPass || gate) Stream(rate, fade, highPass, gate) else null
    }

    /** Soft knee above 0.8 of full scale towards the 0.98 ceiling; slope 1 at the knee, so quieter samples are exact. */
    fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val y = KNEE + (CEILING - KNEE) * tanh((a - KNEE) / (CEILING - KNEE))
        return if (x < 0) -y else y
    }
    private const val KNEE = .8f
    private const val CEILING = .98f

    /** Mono PCM16. Every input sample comes out exactly once: [feed] holds back the last 5 ms, [finish] releases them. */
    class Stream(rate: Int, private val fade: Boolean, private val highPass: Boolean, private val gate: Boolean) {
        private val delay = IntArray(maxOf(1, rate / 200))   // 5 ms: fade-out length and the gate's look-ahead
        private var held = 0
        private var head = 0
        private var emitted = 0L
        // Butterworth high-pass, 70 Hz (RBJ biquad, direct form I).
        private val b0: Double; private val b1: Double; private val b2: Double; private val a1: Double; private val a2: Double
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        // Pause gate: peak envelope, -15 dB below -48 dBFS, opens in 2 ms (with the look-ahead), closes in 80 ms.
        private var envelope = 0.0
        private var gain = 1.0
        private val envelopeRelease = exp(-1.0 / (rate * .08))
        private val gainOpen = exp(-1.0 / (rate * .002))
        private val gainClose = exp(-1.0 / (rate * .08))
        init {
            val w = 2 * PI * 70.0 / rate
            val alpha = sin(w) / (2 * sqrt(.5))
            val a0 = 1 + alpha
            b0 = (1 + cos(w)) / 2 / a0; b1 = -(1 + cos(w)) / a0; b2 = b0
            a1 = -2 * cos(w) / a0; a2 = (1 - alpha) / a0
        }

        fun feed(bytes: ByteArray): ByteArray {
            require(bytes.size % 2 == 0)
            val out = java.io.ByteArrayOutputStream(bytes.size)
            for (i in 0 until bytes.size / 2) {
                var x = ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toDouble()
                if (highPass) {
                    val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                    x2 = x1; x1 = x; y2 = y1; y1 = y; x = y
                }
                if (gate) { val a = abs(x); envelope = if (a > envelope) a else a + (envelope - a) * envelopeRelease }
                val sample = x.toInt().coerceIn(-32768, 32767)
                if (held < delay.size) { delay[(head + held) % delay.size] = sample; held++; continue }
                write(out, delay[head], 1.0)
                delay[head] = sample; head = (head + 1) % delay.size
            }
            return out.toByteArray()
        }

        fun finish(): ByteArray {
            val out = java.io.ByteArrayOutputStream(held * 2)
            val n = held
            for (i in 0 until n) {
                write(out, delay[head], if (fade) (n - i).toDouble() / (n + 1) else 1.0)
                head = (head + 1) % delay.size
            }
            held = 0
            return out.toByteArray()
        }

        private fun write(out: java.io.ByteArrayOutputStream, sample: Int, tail: Double) {
            var g = tail
            if (fade && emitted < delay.size) g *= (emitted + 1).toDouble() / (delay.size + 1)
            if (gate) {
                val target = if (envelope >= GATE_THRESHOLD) 1.0 else GATE_FLOOR
                gain = target + (gain - target) * if (target > gain) gainOpen else gainClose
                g *= gain
            }
            emitted++
            val v = Math.round(sample * g).toInt().coerceIn(-32768, 32767)
            out.write(v and 255); out.write((v shr 8) and 255)
        }
        private companion object {
            const val GATE_THRESHOLD = 32768 * .004   // -48 dBFS
            const val GATE_FLOOR = .18                // -15 dB
        }
    }
}
