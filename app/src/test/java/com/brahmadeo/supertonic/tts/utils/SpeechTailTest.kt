package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeechTailTest {
    private fun tone(n: Int, rate: Int, envelope: (Int) -> Float) =
        FloatArray(n) { (sin(2 * PI * 180 * it / rate) * envelope(it)).toFloat() }

    @Test fun decayIntoSilenceIsNotAbrupt() {
        val rate = 48000
        val speech = tone(rate, rate) { i -> if (i < rate / 2) .5f else (.5f * (1 - (i - rate / 2f) / (rate / 4f))).coerceAtLeast(0f) }
        assertFalse(SpeechTail.inspect(speech, rate).abrupt)
    }

    @Test fun fullLevelCutIsAbrupt() {
        val rate = 48000
        assertTrue(SpeechTail.inspect(tone(rate / 2, rate) { .5f }, rate).abrupt)
    }
}
