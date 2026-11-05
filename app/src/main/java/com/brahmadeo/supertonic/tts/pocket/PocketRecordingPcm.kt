package com.brahmadeo.supertonic.tts.pocket

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decode channel frames directly into bounded mono blocks. Never retain the original multichannel PCM. */
internal class PocketRecordingPcm(val rate: Int, private val channels: Int, private val floating: Boolean,
                                  private val maxSamples: Int) {
    class Failure(message: String) : Exception(message)
    private val blocks = ArrayList<FloatArray>()
    private var count = 0
    private val durationLimit = rate * 120
    val full get() = count == durationLimit

    init {
        if (rate !in 8000..192000) throw Failure("необычная частота записи: $rate Гц")
        if (channels !in 1..8) throw Failure("неподдерживаемое число каналов записи: $channels")
        if (maxSamples < 1) throw Failure("недостаточно свободной памяти для записи")
    }

    fun append(input: ByteBuffer) {
        val bytes = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = channels * if (floating) 4 else 2
        if (bytes.remaining() % frameBytes != 0) throw Failure("неполный звуковой кадр в записи")
        val frames = minOf(bytes.remaining() / frameBytes, durationLimit - count)
        // Check before allocating, not after ByteArrayOutputStream has already doubled its backing array.
        if (count.toLong() + frames > maxSamples) throw Failure("запись слишком большая для обработки; выберите более короткий фрагмент или запись с частотой 24–48 кГц")
        repeat(frames) {
            var sample = 0.0
            repeat(channels) {
                val value = if (floating) bytes.float else bytes.short / 32768f
                if (!value.isFinite()) throw Failure("в записи некорректные звуковые отсчёты")
                sample += value
            }
            if (count % BLOCK == 0) blocks.add(FloatArray(minOf(BLOCK, maxSamples - count, durationLimit - count)))
            blocks.last()[count % BLOCK] = (sample / channels).toFloat()
            count++
        }
    }

    fun samples(): FloatArray {
        val result = FloatArray(count)
        var offset = 0
        for (block in blocks) {
            val size = minOf(block.size, count - offset)
            block.copyInto(result, offset, 0, size)
            offset += size
        }
        return result
    }

    companion object {
        private const val BLOCK = 4096
        const val MAX_SAMPLES = 6_000_000 // <24 MiB, regardless of channels or input encoding
    }
}
