package com.brahmadeo.supertonic.tts.utils

/** Mono PCM16 linear resampling with a bounded sample history across chunks. Duration/pitch are preserved;
 * chunk boundaries never duplicate or omit a sample. Gain is already set by the source engine. Downsampling first
 * removes what the target rate cannot carry (a windowed-sinc low-pass): otherwise sibilants of a 48 kHz Silero
 * or 44.1 kHz Tera voice fold back into the audible band of a 24 kHz main model. */
class StreamingPcmResampler(private val sourceRate: Int, private val targetRate: Int) {
    init { require(sourceRate in 8000..192000 && targetRate in 8000..192000) }
    private val taps: DoubleArray? = if (targetRate < sourceRate) lowPass(0.45 * targetRate / sourceRate) else null
    private val delay = taps?.let { (it.size - 1) / 2 } ?: 0
    private val window = DoubleArray(taps?.size ?: 0)   // last inputs, newest at the end
    private var filterSeen = 0L                          // samples (inputs and tail zeros) that entered the filter
    private var inputs = 0L                              // real input samples
    private var filterEmitted = 0L                       // filtered samples passed on
    private var received = 0L
    private var emitted = 0L
    private var last = 0
    private var history = IntArray(0)
    private val historyLimit = (sourceRate + targetRate - 1) / targetRate + 1
    private var ended = false
    fun feed(bytes: ByteArray): ByteArray {
        check(!ended); require(bytes.size % 2 == 0)
        if (bytes.isEmpty()) return bytes
        inputs += bytes.size / 2
        return interpolate(if (taps == null) bytes else filter(bytes, bytes.size / 2))
    }

    /** Filtered samples, delayed by [delay] so the filter is centred; the first [delay] outputs would precede
     * the stream and are dropped, the last ones come out of [finish]. Exactly one output per input overall. */
    private fun filter(bytes: ByteArray?, count: Int): ByteArray {
        val h = taps!!
        val out = java.io.ByteArrayOutputStream(count * 2)
        for (i in 0 until count) {
            val x = if (bytes == null) 0.0 else ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toDouble()
            System.arraycopy(window, 1, window, 0, window.size - 1); window[window.size - 1] = x
            filterSeen++
            if (filterSeen <= delay) continue
            var y = 0.0
            for (k in h.indices) y += h[k] * window[window.size - 1 - k]
            val v = Math.round(y).toInt().coerceIn(-32768, 32767)
            out.write(v and 255); out.write((v shr 8) and 255); filterEmitted++
        }
        return out.toByteArray()
    }

    private fun interpolate(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        val start = received
        val count = bytes.size / 2
        received += count
        fun sample(index: Long): Int {
            if (index < start) return history[(history.size + index - start).toInt()]
            val p = ((index - start) * 2).toInt()
            return ((bytes[p].toInt() and 255) or (bytes[p + 1].toInt() shl 8)).toShort().toInt()
        }
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            if (emitted >= received * targetRate / sourceRate) break
            val position = emitted * sourceRate
            val left = position / targetRate
            val fraction = position % targetRate
            if (left >= received || (fraction != 0L && left + 1 >= received)) break
            val a = sample(left)
            val value = if (fraction == 0L) a else (a + (sample(left + 1) - a) * fraction.toDouble() / targetRate).toInt()
            output.write(value and 255); output.write((value shr 8) and 255); emitted++
        }
        last = sample(received - 1)
        history = IntArray(minOf(historyLimit.toLong(), received).toInt()) { i -> sample(received - minOf(historyLimit.toLong(), received) + i) }
        return output.toByteArray()
    }
    fun finish(): ByteArray {
        check(!ended)
        // The filter's tail: zeros push out the samples still owed, exactly one output per real input.
        val owed = java.io.ByteArrayOutputStream()
        if (taps != null) while (filterEmitted < inputs) owed.write(filter(null, 1))
        val tail = interpolate(owed.toByteArray())
        ended = true
        return tail + finishInterpolation()
    }

    private fun finishInterpolation(): ByteArray {
        val remaining = (received * targetRate / sourceRate - emitted).coerceAtLeast(0).toInt()
        return ByteArray(remaining * 2).also { bytes -> for (i in 0 until remaining) {
            bytes[i * 2] = last.toByte(); bytes[i * 2 + 1] = (last shr 8).toByte()
        } }
    }

    private companion object {
        /** Odd-length windowed-sinc (Blackman) low-pass, unity gain at DC; [cutoff] in cycles per sample. */
        fun lowPass(cutoff: Double, size: Int = 63): DoubleArray {
            val m = (size - 1) / 2.0
            val h = DoubleArray(size) { n ->
                val x = n - m
                val sinc = if (x == 0.0) 2 * cutoff else kotlin.math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x)
                val w = 0.42 - 0.5 * kotlin.math.cos(2 * Math.PI * n / (size - 1)) + 0.08 * kotlin.math.cos(4 * Math.PI * n / (size - 1))
                sinc * w
            }
            val sum = h.sum()
            return DoubleArray(size) { h[it] / sum }
        }
    }
}
