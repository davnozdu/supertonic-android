package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * Speeds a model cannot say cleanly: the model speaks at its limit and the audio is time-stretched with
 * WSOLA (waveform-similarity overlap-add, pitch kept), the way audiobook players speed up recordings —
 * every sound stays, only time is compressed. Ported from the TeraTTS fork (github.com/9208499-ship-it/
 * teratts-android, GPL-3.0, rust/src/stretch.rs); Tera applies it inside its engine ([com.brahmadeo.
 * supertonic.tts.tera.TeraTimeStretch]), the other Russian models through [split] in SupertonicTTS.
 */
object TimeStretch {
    const val PREFIX = "clean_speed_max_"
    /** Fastest speed each model says without swallowing sounds (measured on the phone with Whisper). */
    private val defaults = mapOf(
        AssetManager.KOKORO_MODEL to 2.5f,
        AssetManager.POCKET_MODEL to 2.5f,
        AssetManager.SILERO_MODEL to 2.5f,
        AssetManager.SILERO_CIS_MODEL to 2.5f,
    )
    fun maxSpeed(context: Context, model: String): Float =
        context.getSharedPreferences("SupertonicPrefs", 0).getFloat(PREFIX + model, defaults[model] ?: 2.5f).coerceIn(1f, 2.5f)

    /** Requested speed → (speed for the model, extra stretch rate) for [model]. */
    fun split(context: Context, model: String, speed: Float): Pair<Float, Float> {
        val s = if (speed.isFinite() && speed > 0f) speed else 1f
        val spoken = s.coerceAtMost(maxSpeed(context, model))
        return spoken to s / spoken
    }

    fun needed(rate: Float) = rate.isFinite() && abs(rate - 1f) >= 0.01f

    /** 16-bit little-endian PCM stretched by [rate]. */
    fun stretchPcm16(pcm: ByteArray, rate: Float, sampleRate: Int): ByteArray {
        if (!needed(rate) || pcm.size < 4) return pcm
        val x = FloatArray(pcm.size / 2) { i -> ((pcm[2 * i].toInt() and 255) or (pcm[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
        val y = stretch(x, rate, sampleRate)
        val out = ByteArray(y.size * 2)
        for (i in y.indices) {
            val v = (y[i] * 32768f).toInt().coerceIn(-32768, 32767)
            out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    /** [x] played [rate] times faster (rate > 1 = shorter), pitch kept. */
    fun stretch(x: FloatArray, rate: Float, sampleRate: Int = 44100): FloatArray {
        if (!needed(rate)) return x
        val outLen = (x.size / rate).toInt()
        if (x.size < 4096) return FloatArray(outLen) { i -> // too short for WSOLA: a few tens of ms
            val p = i * rate; val a = p.toInt(); val f = p - a
            val s0 = x.getOrElse(a) { 0f }; val s1 = x.getOrElse(a + 1) { s0 }; s0 + (s1 - s0) * f
        }
        val n = ((0.030f * sampleRate).toInt()) and 1.inv() // 30 ms frame
        val hopOut = n / 2 // 50 % overlap
        val hopIn = hopOut * rate
        val tol = (0.010f * sampleRate).toInt() // ±10 ms search
        val overlap = n - hopOut
        // Hann window: with 50 % overlap the shifted copies sum to 1.
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val out = FloatArray(outLen + n); val norm = FloatArray(outLen + n)
        fun get(i: Int) = if (i >= 0 && i < x.size) x[i] else 0f
        var prev = 0; var k = 0
        while (true) {
            val outPos = k * hopOut
            if (outPos >= outLen) break
            val nominal = (k * hopIn).toInt()
            val pos = if (k == 0) 0 else {
                // The natural continuation of the previous frame starts at prev + hopOut;
                // pick the offset around nominal whose waveform matches it best.
                val target = prev + hopOut
                var best = nominal; var bestScore = -Float.MAX_VALUE; var d = -tol
                while (d <= tol) {
                    val cand = nominal + d; var score = 0f; var j = 0
                    while (j < overlap) { score += get(cand + j) * get(target + j); j += 2 }
                    if (score > bestScore) { bestScore = score; best = cand }
                    d += 2
                }
                max(best, 0)
            }
            for (i in 0 until n) { out[outPos + i] += get(pos + i) * win[i]; norm[outPos + i] += win[i] }
            prev = pos; k++
        }
        for (i in out.indices) if (norm[i] > 1e-3f) out[i] /= norm[i]
        return out.copyOf(outLen)
    }
}
