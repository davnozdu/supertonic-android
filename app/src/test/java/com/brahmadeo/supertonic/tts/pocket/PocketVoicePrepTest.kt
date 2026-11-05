package com.brahmadeo.supertonic.tts.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class PocketVoicePrepTest {
    private val rate = PocketVoicePrep.RATE

    /** "Speech": 1.5 s voiced bursts (harmonics) with [gap] s pauses, a 50 Hz [hum] and a little noise under it all. */
    private fun recording(seconds: Float, gap: Float = .3f, amp: Float = .3f, hum: Float = .05f): FloatArray {
        val rnd = java.util.Random(1)
        return FloatArray((seconds * rate).toInt()) { i ->
            val t = i.toFloat() / rate
            val cycle = 1.5f + gap
            val voiced = (t % cycle) < 1.5f
            val s = if (voiced) amp * (sin(2 * PI * 140 * t) + .5 * sin(2 * PI * 280 * t) + .25 * sin(2 * PI * 3500 * t)).toFloat() / 1.75f else 0f
            s + hum * sin(2 * PI * 50 * t).toFloat() + .001f * rnd.nextGaussian().toFloat()
        }
    }

    private fun rms(x: FloatArray) = sqrt(x.fold(0.0) { a, v -> a + v * v } / x.size)

    @Test
    fun longRecordingGivesSixToTenSecondsLevelled() {
        val x = PocketVoicePrep.clean(recording(30f))
        val clip = PocketVoicePrep.pick(x)
        val s = PocketVoicePrep.seconds(clip)
        assertTrue("piece $s s", s in 5.5f..10.5f)
        assertTrue(clip.maxOf { abs(it) } <= .9f)
        assertTrue("levelled", rms(clip) > .03)
    }

    @Test
    fun humIsCut() {
        val x = PocketVoicePrep.highPass(FloatArray(rate) { .5f * sin(2 * PI * 30 * it / rate).toFloat() }, 70.0)
        assertTrue(rms(x.copyOfRange(rate / 2, rate)) < .1)
    }

    @Test
    fun longHesitationsAreShortened() {
        val x = recording(20f, gap = 2f)
        val y = PocketVoicePrep.clean(x)
        assertTrue("${y.size} vs ${x.size}", y.size < x.size * .8)
    }

    @Test
    fun clippedPeaksAreFoundAndRounded() {
        val x = FloatArray(rate) { (1.6 * sin(2 * PI * 200 * it / rate)).toFloat().coerceIn(-1f, 1f) }
        val (y, runs) = PocketVoicePrep.declip(x)
        assertTrue(runs >= 10)
        assertTrue(y.maxOf { abs(it) } > 1f)
        assertTrue(PocketVoicePrep.check(x, 48000, runs).clipped)
    }

    @Test
    fun cleanRecordingRaisesNoWarnings() {
        val x = recording(12f, hum = 0f)
        val r = PocketVoicePrep.check(x, 48000, 0)
        assertFalse(r.clipped); assertFalse(r.noisy)
        assertTrue(PocketVoicePrep.check(x, 16000, 0).muffled)
    }

    @Test
    fun resampleKeepsDurationAndPitch() {
        val x = FloatArray(48000) { sin(2 * PI * 440 * it / 48000).toFloat() }
        val y = PocketVoicePrep.resample(x, 48000, 24000)
        assertEquals(24000, y.size)
        val mid = y.copyOfRange(1000, 23000)
        assertTrue(abs(rms(mid) - sqrt(.5)) < .02)
    }

    @Test(expected = PocketVoicePrep.Failure::class)
    fun silenceIsRefused() { PocketVoicePrep.pick(FloatArray(rate * 8)) }
}
