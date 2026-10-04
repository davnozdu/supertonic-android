package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.abs

class SpeechLoudnessTest {
    private fun wave(level: Float) = FloatArray(48000) { (sin(it * .1) * level).toFloat() }
    private fun decoded(bytes: ByteArray) = FloatArray(bytes.size / 2) { i ->
        ((bytes[i*2].toInt() and 255) or (bytes[i*2+1].toInt() shl 8)).toShort() / 32767f
    }
    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    @Test fun quietAndLoudVoicesReachTheSameLevelWithoutClipping() {
        val quiet=wave(.05f); val loud=wave(.5f)
        val a=decoded(SpeechLoudness.pcm(quiet,SpeechLoudness.scale(listOf(quiet),2.5f)))
        val b=decoded(SpeechLoudness.pcm(loud,SpeechLoudness.scale(listOf(loud),2.5f)))
        assertEquals(rms(a),rms(b),.001)
        assertTrue(a.all { abs(it) < .98f } && b.all { abs(it) < .98f })
    }
    @Test fun silenceDoesNotReducePhraseLoudnessOrGetAmplified() {
        val sound=wave(.1f)
        val scale=SpeechLoudness.scale(listOf(sound),2.5f)
        assertEquals(scale,SpeechLoudness.scale(listOf(FloatArray(96000),sound),2.5f),.00001f)
        assertEquals(0f,SpeechLoudness.scale(listOf(FloatArray(48000)),2.5f),0f)
    }
    @Test fun phaseDynamicsAndSampleCountArePreserved() {
        val source=wave(.2f)
        val level=SpeechLoudness.scale(listOf(source),2.5f)
        val out=decoded(SpeechLoudness.pcm(source,level))
        assertEquals(source.size,out.size)
        for(i in source.indices step 997) assertEquals(source[i]*level,out[i],.00004f)
    }
    @Test fun teraChunkBoundariesDoNotActLikePerWordAutomaticGain() {
        val stream=SpeechLoudness.Stream(2.5f,true)
        val strong=wave(.2f); val soft=wave(.02f)
        val a=decoded(stream.pcm(strong)); val b=decoded(stream.pcm(soft))
        assertEquals(10.0,rms(a)/rms(b),.02)
        assertEquals(soft.size,b.size)
    }
    @Test fun invalidSamplesAndGainAreRejectedAndBoostIsBounded() {
        assertThrows(IllegalArgumentException::class.java) { SpeechLoudness.scale(listOf(floatArrayOf(Float.NaN)),2.5f) }
        assertThrows(IllegalArgumentException::class.java) { SpeechLoudness.scale(listOf(wave(.1f)),Float.POSITIVE_INFINITY) }
        val source=floatArrayOf(-2f,1f,0f,2f)
        val bytes=decoded(SpeechLoudness.pcm(source,SpeechLoudness.scale(listOf(source),2.5f)))
        assertTrue(bytes.all { abs(it) <= .98f })
        assertEquals(0f,SpeechLoudness.scale(listOf(source),0f),0f)
    }
}
