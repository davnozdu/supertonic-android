package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

class VoiceTempoTest {
    private fun pcm(silenceMs: Int, speechMs: Int, tailMs: Int, rate: Int = 24000): ByteArray {
        val total = (silenceMs + speechMs + tailMs) * rate / 1000
        val start = silenceMs * rate / 1000; val end = (silenceMs + speechMs) * rate / 1000
        return ByteArray(total * 2).also { b ->
            for (i in start until end) {
                val v = (sin(i * .2) * 8000).toInt()
                b[i * 2] = v.toByte(); b[i * 2 + 1] = (v shr 8).toByte()
            }
        }
    }

    @Test fun speechDurationIgnoresSilenceAround() {
        assertEquals(2000.0, VoiceTempo.speechDurationMs(pcm(300, 2000, 400), 24000).toDouble(), 20.0)
        assertEquals(0L, VoiceTempo.speechDurationMs(ByteArray(4800), 24000))
    }

    @Test fun slowerVoiceIsSpedUpWithinBounds() {
        assertEquals(1.2f, VoiceTempo.factor(3600, 3000), .001f)   // 20% slower voice reads 20% faster
        assertEquals(.8f, VoiceTempo.factor(2400, 3000), .001f)
        assertEquals(1.5f, VoiceTempo.factor(9000, 3000), .001f)
        assertEquals(4480f / 3050, VoiceTempo.factor(4480, 3050), .001f)   // Silero aidar against eugene, measured
        assertEquals(.67f, VoiceTempo.factor(1000, 3000), .001f)
        assertEquals(1f, VoiceTempo.factor(0, 3000), .001f)
    }
}
