package com.brahmadeo.supertonic.tts.foreign

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object ForeignPcm {
    fun convert(bytes: ByteArray, rate: Int, channels: Int, floating: Boolean, targetRate: Int, gain: Float): ByteArray {
        require(rate in 8000..192000 && channels in 1..2 && targetRate in 8000..192000)
        val width = if (floating) 4 else 2
        require(bytes.size % (width * channels) == 0)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val mono = FloatArray(bytes.size / width / channels)
        var peak = 0f
        for (i in mono.indices) {
            var sum = 0f
            repeat(channels) { sum += if (floating) input.float else input.short / 32768f }
            mono[i] = sum / channels; require(mono[i].isFinite()); peak = maxOf(peak, kotlin.math.abs(mono[i]))
        }
        if (mono.isEmpty()) return ByteArray(0)
        val safeGain = if (peak > 0) minOf(gain.coerceAtLeast(0f), .98f / peak) else 1f
        val length = mono.size.toLong() * targetRate / rate
        require(length <= 4_000_000)
        val size = length.toInt()
        val output = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until size) {
            val pos = i.toDouble() * rate / targetRate; val left = pos.toInt().coerceAtMost(mono.lastIndex)
            val right = minOf(left + 1, mono.lastIndex); val fraction = (pos - left).toFloat()
            val sample = mono[left] + (mono[right] - mono[left]) * fraction
            output.putShort((sample * safeGain * 32767).toInt().coerceIn(-32768, 32767).toShort())
        }
        return output.array()
    }
}
