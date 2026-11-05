package com.brahmadeo.supertonic.tts.pocket

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PocketRecordingPcmTest {
    @Test fun stereoChunksBecomeMonoBeforeStorage() {
        val pcm = PocketRecordingPcm(48000, 2, false, 8)
        val input = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(32767).putShort(-32767).putShort(16384).putShort(16384).putShort(-16384).putShort(0)
        input.flip(); pcm.append(input)
        assertArrayEquals(floatArrayOf(0f, .5f, -.25f), pcm.samples(), 0f)
    }

    @Test fun oversizeChunkIsRejectedBeforeGrowingCollector() {
        val pcm = PocketRecordingPcm(192000, 8, false, 4)
        pcm.append(ByteBuffer.allocate(4 * 8 * 2))
        try { pcm.append(ByteBuffer.allocate(8 * 2)); fail("memory limit ignored") }
        catch (_: PocketRecordingPcm.Failure) {}
        assertEquals(4, pcm.samples().size)
    }

    @Test fun partialBlockCopiesOnlyValidFramesAndKeepsFloatPrecision() {
        val pcm = PocketRecordingPcm(24000, 1, true, 10000)
        for (size in listOf(4100, 4100, 1800)) pcm.append(ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(size) { putFloat(.125f) }; flip()
        })
        assertEquals(10000, pcm.samples().size)
        assertTrue(pcm.samples().all { it == .125f })
    }

    @Test(expected = PocketRecordingPcm.Failure::class)
    fun nonFiniteAudioCannotReachCleaning() {
        PocketRecordingPcm(24000, 1, true, 10).append(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).apply { flip() })
    }

    @Test(expected = PocketRecordingPcm.Failure::class)
    fun unsupportedChannelsAreRejected() { PocketRecordingPcm(48000, 1000, false, 10) }

    @Test fun durationLimitKeepsExactlyFirstTwoMinutes() {
        val pcm = PocketRecordingPcm(8000, 1, false, 1_000_000)
        pcm.append(ByteBuffer.allocate(960_002 * 2))
        assertTrue(pcm.full)
        assertEquals(960_000, pcm.samples().size)
    }
}
