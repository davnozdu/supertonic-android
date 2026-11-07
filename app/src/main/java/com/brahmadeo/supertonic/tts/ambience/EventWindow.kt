package com.brahmadeo.supertonic.tts.ambience

import kotlin.math.*

/** Locate an event by positive spectral flux and energy; keep context instead of taking the first eight seconds. */
internal object EventWindow {
    fun crop(pcm: FloatArray, rate: Int, seconds: Int = 8): FloatArray {
        val frames = pcm.size / 2
        val wanted = rate * seconds
        if (frames <= wanted) return pcm
        val hop = maxOf(1, rate / 25)
        val stride = maxOf(1, rate / 8000)
        val size = 128
        val cosines = Array(16) { b -> DoubleArray(size) { i -> cos(2 * PI * (b + 1) * i / size) * (0.5 - 0.5 * cos(2 * PI * i / (size - 1))) } }
        val sines = Array(16) { b -> DoubleArray(size) { i -> sin(2 * PI * (b + 1) * i / size) * (0.5 - 0.5 * cos(2 * PI * i / (size - 1))) } }
        val previous = DoubleArray(16)
        val score = DoubleArray((frames + hop - 1) / hop)
        for (n in score.indices) {
            val at = n * hop
            var energy = 0.0
            for (b in 0 until 16) {
                var re = 0.0; var im = 0.0
                for (i in 0 until size) {
                    val f = at + i * stride
                    val x = if (f < frames) (pcm[f * 2] + pcm[f * 2 + 1]) * 0.5 else 0.0
                    re += x * cosines[b][i]; im += x * sines[b][i]
                }
                val power = sqrt(re * re + im * im)
                energy += power + maxOf(0.0, power - previous[b]) * 2
                previous[b] = power
            }
            score[n] = energy
        }
        val span = maxOf(1, wanted / hop)
        var sum = score.take(span).sum(); var best = sum; var start = 0
        for (i in span until score.size) {
            sum += score[i] - score[i - span]
            if (sum > best) { best = sum; start = (i - span + 1) * hop }
        }
        start = (start - rate / 4).coerceIn(0, frames - wanted)
        return pcm.copyOfRange(start * 2, (start + wanted) * 2)
    }
}
