package com.brahmadeo.supertonic.tts.foreign

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ForeignPcmTest {
    @Test fun rateConversionPreservesDurationAndStereoBecomesMono() {
        val stereo = ByteBuffer.allocate(24000 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(24000) { stereo.putShort(12000); stereo.putShort(6000) }
        val mono = ForeignPcm.convert(stereo.array(), 24000, 2, false, 48000, 1f)
        assertEquals(48000 * 2, mono.size)
        val sample = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        assertTrue(sample in 8990..9010)
    }
    @Test fun floatingAudioBoostCannotClip() {
        val data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(.9f).putFloat(-.9f).array()
        val pcm = ByteBuffer.wrap(ForeignPcm.convert(data, 48000, 1, true, 48000, 2.5f)).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(pcm.short.toInt() in 32000..32200)
        assertTrue(pcm.short.toInt() in -32200..-32000)
    }
}
