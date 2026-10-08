package com.brahmadeo.supertonic.tts.utils

/** Mono PCM16 linear resampling with a bounded sample history across chunks. Duration/pitch are preserved;
 * chunk boundaries never duplicate or omit a sample. Gain is already set by the source engine. */
class StreamingPcmResampler(private val sourceRate: Int, private val targetRate: Int) {
    init { require(sourceRate in 8000..192000 && targetRate in 8000..192000) }
    private var received = 0L
    private var emitted = 0L
    private var last = 0
    private var history = IntArray(0)
    private val historyLimit = (sourceRate + targetRate - 1) / targetRate + 1
    private var ended = false
    fun feed(bytes: ByteArray): ByteArray {
        check(!ended); require(bytes.size % 2 == 0)
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
        check(!ended); ended = true
        val remaining = (received * targetRate / sourceRate - emitted).coerceAtLeast(0).toInt()
        return ByteArray(remaining * 2).also { bytes -> for (i in 0 until remaining) {
            bytes[i * 2] = last.toByte(); bytes[i * 2 + 1] = (last shr 8).toByte()
        } }
    }
}
