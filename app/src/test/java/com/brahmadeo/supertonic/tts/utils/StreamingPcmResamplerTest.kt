package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StreamingPcmResamplerTest {
    private fun pcm(n: Int) = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN).also { b ->
        repeat(n) { b.putShort((kotlin.math.sin(it * .13) * 18000).toInt().toShort()) }
    }.array()
    private fun convert(bytes: ByteArray, source: Int, target: Int, split: Int): ByteArray {
        val converter = StreamingPcmResampler(source, target)
        val output = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < bytes.size) { val end = minOf(i + split * 2, bytes.size); output.write(converter.feed(bytes.copyOfRange(i, end))); i = end }
        output.write(converter.finish()); return output.toByteArray()
    }
    @Test fun changingChunkSizesDoesNotChangeTheWaveform() {
        val bytes = pcm(12347)
        for (source in listOf(24000, 44100, 48000)) for (target in listOf(24000, 44100, 48000)) {
            val whole = convert(bytes, source, target, bytes.size / 2)
            assertEquals(bytes.size.toLong() / 2 * target / source * 2, whole.size.toLong())
            for (split in listOf(1, 7, 512, 24000)) assertTrue(whole.contentEquals(convert(bytes, source, target, split)))
        }
    }
    @Test fun identityPreservesEverySampleAndEmptyIsEmpty() {
        val bytes = pcm(321)
        assertTrue(bytes.contentEquals(convert(bytes, 44100, 44100, 1)))
        assertEquals(0, convert(ByteArray(0), 48000, 24000, 1).size)
        assertEquals(0, convert(pcm(1), 48000, 24000, 1).size)
    }
    @Test fun oneSecondRetainsDurationAcrossAllModelRates() {
        for (source in listOf(24000, 44100, 48000)) for (target in listOf(24000, 44100, 48000)) {
            assertEquals(target * 2, convert(pcm(source), source, target, 901).size)
        }
    }
    private fun sine(n: Int, rate: Int, hz: Double, amplitude: Double = 10000.0) =
        ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN).also { b ->
            repeat(n) { b.putShort((kotlin.math.sin(2 * Math.PI * hz * it / rate) * amplitude).toInt().toShort()) }
        }.array()
    private fun rms(bytes: ByteArray): Double {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        // Skip the edges: only the steady state matters.
        val from = b.limit() / 10; val to = b.limit() - from
        return kotlin.math.sqrt((from until to).sumOf { b.get(it).toDouble().let { v -> v * v } } / (to - from))
    }
    @Test fun downsamplingRemovesWhatTheTargetCannotCarryAndKeepsSpeech() {
        // 18 kHz at 48 kHz cannot exist at 24 kHz: without filtering it would come back as a 6 kHz tone.
        val high = convert(sine(48000, 48000, 18000.0), 48000, 24000, 1000)
        assertTrue(rms(high) < 0.03 * 10000 / kotlin.math.sqrt(2.0))
        for ((source, hz) in listOf(48000 to 1000.0, 44100 to 3000.0)) {
            val low = convert(sine(source, source, hz), source, 24000, 777)
            assertEquals(24000 * 2, low.size)
            assertEquals(1.0, rms(low) / (10000 / kotlin.math.sqrt(2.0)), 0.05)
        }
    }
}
