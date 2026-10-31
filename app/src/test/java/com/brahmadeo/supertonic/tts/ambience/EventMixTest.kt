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
    fun threeEventsOnOneWordStillStartOneAfterAnother() {
        // Три звука пришлись на одно слово: без разноса они слились бы в один удар.
        val starts = EventMix.scheduled(listOf(1_000L, 1_000L, 1_000L), mix = true, gap = 0)
        assertTrue(starts == listOf(1_000L, 1_220L, 1_440L))
    }

    @Test
    fun eventsAtTheVeryStartOfAPieceDoNotPileUp() {
        // «Звук раньше текста» уводит начало в минус: все три стали бы нулевыми.
        val starts = EventMix.scheduled(listOf(-900L, -500L, -200L), mix = true, gap = 0)
        assertTrue(starts == listOf(0L, 220L, 440L))
    }

    @Test
    fun withoutMixingTheGapFollowsThePreviousStart() {
        val starts = EventMix.scheduled(listOf(500L, 700L, 3_000L), mix = false, gap = 800)
        assertTrue(starts == listOf(500L, 1_300L, 3_000L))
    }

    @Test
    fun aSoundingClipBlocksOnlyWithoutMixing() {
        assertFalse(EventMix.blocked(mix = true, busy = true))
        assertTrue(EventMix.blocked(mix = false, busy = true))
        assertFalse(EventMix.blocked(mix = false, busy = false))
    }
}
