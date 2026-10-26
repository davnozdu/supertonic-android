package com.brahmadeo.supertonic.tts.tera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class TeraTimeStretchTest {
    // A harmonic "voice": f0 plus overtones, slow amplitude envelope.
    private fun tone(f0: Float, seconds: Float, rate: Int = 44100) = FloatArray((seconds * rate).toInt()) { i ->
        val t = i.toFloat() / rate
        val env = 0.6f + 0.4f * sin(2 * PI * 3 * t).toFloat()
        env * (0 until 5).sumOf { h -> sin(2 * PI * f0 * (h + 1) * t) / (h + 1) }.toFloat() * 0.3f
    }
    private fun crossingsPerSecond(x: FloatArray, rate: Int = 44100) =
        (1 until x.size).count { (x[it - 1] < 0f) != (x[it] < 0f) } / (x.size.toFloat() / rate)

    @Test fun splitsSpeedIntoModelAndStretch() {
        assertEquals(1f to 1f, TeraTimeStretch.splitSpeed(1f))
        val (m, r) = TeraTimeStretch.splitSpeed(2.5f)
        assertEquals(TeraTimeStretch.MODEL_MAX_SPEED, m)
        assertEquals(2.5f, m * r, 1e-5f)
        val (m2, r2) = TeraTimeStretch.splitSpeed(0.5f)
        assertEquals(TeraTimeStretch.MODEL_MIN_SPEED, m2)
        assertEquals(0.5f, m2 * r2, 1e-5f)
    }

    @Test fun stretchKeepsPitchAndChangesLength() {
        val x = tone(140f, 3f)
        for (rate in listOf(1.5f, 2f, 0.7f)) {
            val y = TeraTimeStretch.stretch(x, rate)
            val want = x.size / rate
            assertTrue("length at $rate", abs(y.size - want) < 0.01f * want)
            assertTrue("pitch drift at $rate", abs(crossingsPerSecond(y) / crossingsPerSecond(x) - 1f) < 0.08f)
            assertTrue(y.all { it.isFinite() && abs(it) < 1.5f })
        }
    }

    @Test fun phraseEndRunsOnWhileAudibleButNotAcrossAGap() {
        val speech = tone(140f, 0.5f)
        val predicted = speech.size - 2000 // the model's duration ends inside the last sound
        val peak = TeraTimeStretch.peakRms(speech, 0, predicted)
        val ext = TeraTimeStretch.extension(speech, predicted, peak)
        assertEquals(2000, ext) // still sounding: taken up to the end (< 80 ms)
        // a quiet gap and then a click: the click is not taken in
        val withClick = speech.copyOf(predicted) + FloatArray(1500) + FloatArray(300) { 0.8f }
        assertEquals(0, TeraTimeStretch.extension(withClick, predicted, peak))
    }

    @Test fun fadeOutEndsInSilence() {
        val x = FloatArray(2000) { 0.5f }
        TeraTimeStretch.fadeOut(x)
        assertTrue(abs(x.last()) < 0.01f)
        assertEquals(0.5f, x[1000], 0f)
    }
}
