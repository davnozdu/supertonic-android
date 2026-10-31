package com.brahmadeo.supertonic.tts.ambience

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Максимальная плотность: при наложении не пропускается ни один размеченный звук. */
class EventMixTest {

    @Test
    fun mixingNeverWaitsForTheGap() {
        assertTrue(EventMix.allowed(mix = true, gap = 800, at = 100, lastAt = 90))
        assertTrue(EventMix.allowed(mix = true, gap = 3000, at = 0, lastAt = 0))
    }

    @Test
    fun withoutMixingTheGapIsKept() {
        assertFalse(EventMix.allowed(mix = false, gap = 800, at = 500, lastAt = 90))
        assertTrue(EventMix.allowed(mix = false, gap = 800, at = 1000, lastAt = 90))
        assertTrue(EventMix.allowed(mix = false, gap = 0, at = 100, lastAt = 90))
    }

    @Test
    fun aSoundingClipBlocksOnlyWithoutMixing() {
        assertFalse(EventMix.blocked(mix = true, busy = true))
        assertTrue(EventMix.blocked(mix = false, busy = true))
        assertFalse(EventMix.blocked(mix = false, busy = false))
    }
}
