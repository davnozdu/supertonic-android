package com.brahmadeo.supertonic.tts.utils

import kotlin.math.abs
import kotlin.math.sqrt

/** Constant phrase gain: level voices without altering timing or word dynamics. */
object SpeechLoudness {
    fun scale(frames: List<FloatArray>, requestedGain: Float, enabled: Boolean = true): Float {
        require(requestedGain.isFinite())
        var peak = 0f
        frames.forEach { frame -> frame.forEach { sample ->
            require(sample.isFinite()) { "Non-finite speech sample" }
            peak = maxOf(peak, abs(sample))
        } }
        if (peak <= .0005f) return 0f // Do not boost silence/noise.
        val threshold = maxOf(.0005f, peak * .02f)
        var energy = 0.0
        var count = 0L
        frames.forEach { frame -> frame.forEach { sample ->
            if (abs(sample) >= threshold) { energy += sample.toDouble() * sample; count++ }
        } }
        val rms = if (count > 0) sqrt(energy / count).toFloat() else 0f
        val wanted = if (enabled && rms > .001f)
            (.12f / rms).coerceIn(.25f, 4f) * (requestedGain.coerceAtLeast(0f) / 2.5f)
        else requestedGain.coerceAtLeast(0f)
        return minOf(wanted, .98f / peak)
    }

    fun pcm(samples: FloatArray, scale: Float): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, sample ->
            require(sample.isFinite())
            val value = (sample * scale).coerceIn(-.98f, .98f)
            val integer = (value * 32767f).toInt()
            bytes[i * 2] = integer.toByte()
            bytes[i * 2 + 1] = (integer shr 8).toByte()
        }
        return bytes
    }

    /** Tera emits vocoder chunks. Choose one gain at the first audible chunk;
     * never recalculate an AGC envelope after each word or vocoder boundary. */
    class Stream(private val requestedGain: Float, private val enabled: Boolean) {
        private var fixed: Float? = null
        fun pcm(samples: FloatArray): ByteArray {
            if (fixed == null) scale(listOf(samples), requestedGain, enabled).takeIf { it > 0f }?.let { fixed = it }
            return SpeechLoudness.pcm(samples, fixed ?: 0f)
        }
    }
}
