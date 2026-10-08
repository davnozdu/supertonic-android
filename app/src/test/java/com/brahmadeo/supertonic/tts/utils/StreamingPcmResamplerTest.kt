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
}
