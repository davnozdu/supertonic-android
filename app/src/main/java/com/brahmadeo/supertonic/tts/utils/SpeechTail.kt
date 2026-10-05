package com.brahmadeo.supertonic.tts.utils

import kotlin.math.abs
import kotlin.math.sqrt

/** Diagnostic only: does generated speech stop at full level instead of decaying into silence? */
object SpeechTail {
    data class Report(val trailingMs: Int, val tailRatio: Float, val abrupt: Boolean)

    fun inspect(samples: FloatArray, rate: Int): Report {
        val peak = samples.maxOfOrNull { abs(it) } ?: 0f
        if (peak <= 1e-4f) return Report(0, 0f, false)
        val last = samples.indexOfLast { abs(it) > peak * .05f }
        val trailingMs = ((samples.size - 1 - last) * 1000L / rate).toInt()
        val window = (rate * 15 / 1000).coerceAtLeast(1)
        fun rms(from: Int, to: Int): Float {
            var sum = 0.0; var n = 0
            for (i in from.coerceAtLeast(0) until to) { sum += samples[i].toDouble() * samples[i]; n++ }
            return if (n == 0) 0f else sqrt(sum / n).toFloat()
        }
        var active = 0.0; var count = 0
        for (s in samples) if (abs(s) > peak * .05f) { active += s.toDouble() * s; count++ }
        val activeRms = if (count == 0) 0f else sqrt(active / count).toFloat()
        val ratio = if (activeRms > 0f) rms(last + 1 - window, last + 1) / activeRms else 0f
        return Report(trailingMs, ratio, trailingMs < 20 && ratio > .35f)
    }
}
