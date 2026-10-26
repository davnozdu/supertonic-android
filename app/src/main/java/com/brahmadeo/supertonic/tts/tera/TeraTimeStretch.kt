package com.brahmadeo.supertonic.tts.tera

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tera's ends of phrases and speeds outside the model's clean range. Ported from the TeraTTS fork
 * (github.com/9208499-ship-it/teratts-android, GPL-3.0: rust/src/stretch.rs, tera.rs speech_end).
 *
 * - Tera speaks cleanly only within ~0.75…1.5× of its duration scale; pushed further it swallows
 *   syllables. Beyond that the model speaks at its limit and the audio is time-stretched (WSOLA, pitch
 *   kept), the way audiobook players speed up recordings: every sound stays, only time is compressed.
 * - The audio is cut at the predicted duration; a final consonant still sounding there may run on by up
 *   to 80 ms (10 ms windows while audible), never across a quiet gap (no clicks or breaths after the
 *   period), and the last 10 ms fade out so a cut inside sound does not click.
 */
object TeraTimeStretch {
    const val MODEL_MIN_SPEED = 0.75f
    const val MODEL_MAX_SPEED = 1.5f
    private const val WIN = 441 // 10 ms at 44.1 kHz
    private const val MAX_EXT = 3528 // 80 ms

    /** Requested speed → (speed for the model, extra stretch rate). */
    fun splitSpeed(speed: Float): Pair<Float, Float> {
        val s = if (speed.isFinite() && speed > 0f) speed else 1f
        val model = s.coerceIn(MODEL_MIN_SPEED, MODEL_MAX_SPEED)
        return model to s / model
    }

    fun needsStretch(rate: Float) = com.brahmadeo.supertonic.tts.utils.TimeStretch.needed(rate)

    private fun rms(x: FloatArray, from: Int, to: Int): Float {
        var s = 0.0; for (i in from until to) s += x[i].toDouble() * x[i]
        return if (to > from) sqrt(s / (to - from)).toFloat() else 0f
    }

    /** Loudest 10 ms window of [x] up to [until]. */
    fun peakRms(x: FloatArray, from: Int = 0, until: Int = x.size): Float {
        var peak = 0f; var i = from
        while (i < until) { peak = max(peak, rms(x, i, min(i + WIN, until))); i += WIN }
        return peak
    }

    /** How many samples past [predicted] (≤ 80 ms, ≤ what [x] holds) still belong to the speech. */
    fun extension(x: FloatArray, predicted: Int, peak: Float): Int {
        val p = predicted.coerceIn(0, x.size)
        if (p == 0 || p == x.size) return 0
        val threshold = max(peak * 0.03f, 0.002f) // ~30 dB under the loudest part: quiet с, ш, ф still count
        val limit = min(p + MAX_EXT, x.size)
        var end = p
        while (end < limit) {
            val to = min(end + WIN, limit)
            if (rms(x, end, to) > threshold) end = to else break
        }
        return end - p
    }

    fun fadeOut(x: FloatArray, length: Int = x.size) {
        val n = min(length, WIN)
        for (i in 0 until n) x[length - n + i] *= (n - i).toFloat() / n
    }

    /** [x] played [rate] times faster, pitch kept ([com.brahmadeo.supertonic.tts.utils.TimeStretch]). */
    fun stretch(x: FloatArray, rate: Float, sampleRate: Int = 44100) = com.brahmadeo.supertonic.tts.utils.TimeStretch.stretch(x, rate, sampleRate)
}
