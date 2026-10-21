package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class SpeechFiltersTest {
    private fun pcm(samples: IntArray) = ByteArray(samples.size * 2).also { b ->
        samples.forEachIndexed { i, v -> b[i * 2] = v.toByte(); b[i * 2 + 1] = (v shr 8).toByte() }
    }
    private fun decoded(bytes: ByteArray) = IntArray(bytes.size / 2) { i ->
        ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toInt()
    }
    private fun tone(hz: Double, n: Int, level: Double = 8000.0, rate: Int = 24000) = IntArray(n) { (sin(2 * PI * hz * it / rate) * level).toInt() }
    private fun run(stream: SpeechFilters.Stream, input: IntArray, chunk: Int): IntArray {
        val out = java.io.ByteArrayOutputStream()
        var p = 0
        while (p < input.size) { val e = minOf(p + chunk, input.size); out.write(stream.feed(pcm(input.copyOfRange(p, e)))); p = e }
        out.write(stream.finish())
        return decoded(out.toByteArray())
    }
    private fun rms(a: IntArray, from: Int = 0, to: Int = a.size) = sqrt((from until to).sumOf { a[it].toDouble() * a[it] } / (to - from))

    @Test fun everySampleComesOutOnceWhateverTheChunking() {
        val input = tone(300.0, 10_007)
        for (chunk in listOf(1, 7, 120, 4096, 24000)) assertEquals(input.size, run(SpeechFilters.Stream(24000, true, true, true), input, chunk).size)
        assertEquals(50, run(SpeechFilters.Stream(24000, true, true, false), tone(300.0, 50), 3).size) // shorter than the 5 ms delay
    }

    @Test fun chunkingDoesNotChangeTheSound() {
        val input = tone(220.0, 9000)
        val whole = run(SpeechFilters.Stream(44100, true, true, true), input, input.size)
        assertArrayEquals(whole, run(SpeechFilters.Stream(44100, true, true, true), input, 13))
    }

    @Test fun edgesFadeFromAndToSilence() {
        val dc = IntArray(4800) { 10000 }
        val out = run(SpeechFilters.Stream(24000, true, false, false), dc, 500)
        assertTrue(abs(out.first()) < 200 && abs(out.last()) < 200)
        assertEquals(10000, out[2400])
    }

    @Test fun highPassRemovesHumAndKeepsSpeech() {
        val n = 24000
        val hum = run(SpeechFilters.Stream(24000, false, true, false), tone(30.0, n), 1000)
        val voice = run(SpeechFilters.Stream(24000, false, true, false), tone(300.0, n), 1000)
        assertTrue(rms(hum, n / 2, n - 200) < rms(tone(30.0, n)) * .25)
        assertEquals(rms(tone(300.0, n)), rms(voice, n / 2, n - 200), rms(tone(300.0, n)) * .03)
        val offset = run(SpeechFilters.Stream(24000, false, true, false), IntArray(n) { 3000 }, 1000)
        assertTrue(abs(offset[n - 200]) < 30)
    }

    @Test fun pauseGateLowersHissButNotSpeech() {
        val rate = 24000
        val hiss = IntArray(rate) { if (it % 2 == 0) 40 else -40 }          // about -58 dBFS
        val speech = tone(300.0, rate)
        val out = run(SpeechFilters.Stream(rate, false, false, true), hiss + speech, 512)
        assertTrue(rms(out, rate / 2, rate - 200) < 40 * .3)
        assertEquals(rms(speech), rms(out, rate + 2400, 2 * rate - 200), rms(speech) * .01)
    }

    @Test fun limiterIsExactBelowTheKneeAndNeverReachesFullScale() {
        assertEquals(.5f, SpeechFilters.limit(.5f), 0f)
        assertEquals(-.8f, SpeechFilters.limit(-.8f), 0f)
        assertTrue(SpeechFilters.limit(.9f) in .85f..<.9f)
        assertTrue(SpeechFilters.limit(5f) <= .98f && SpeechFilters.limit(-5f) >= -.98f)
        var last = 0f
        for (i in 0..2000) { val y = SpeechFilters.limit(i / 1000f); assertTrue(y >= last); last = y }
    }

    /** The cost claim in «Обработка звука»: a minute of 24 kHz speech with every filter in far less than a second. */
    @Test fun aMinuteOfAudioTakesMilliseconds() {
        val input = tone(250.0, 24000 * 60)
        run(SpeechFilters.Stream(24000, true, true, true), input, 24000) // warm-up
        val t = System.nanoTime()
        run(SpeechFilters.Stream(24000, true, true, true), input, 24000)
        val ms = (System.nanoTime() - t) / 1_000_000
        println("SpeechFilters: 60 s of 24 kHz audio in $ms ms")
        assertTrue(ms < 1000)
    }
}
