package com.brahmadeo.supertonic.tts.ambience

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/** Приглушение голоса: в салоне машины верх гаснет, низ остаётся; в комнате голос не глушится. */
class VoiceRoomTest {

    private fun mono(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[2 * i] = s.toByte(); out[2 * i + 1] = (s shr 8).toByte()
        }
        return out
    }

    private fun rms(pcm: ByteArray, from: Int = 0): Double {
        var sum = 0.0
        var n = 0
        var i = from
        while (i + 1 < pcm.size) {
            val v = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort() / 32768f
            sum += v.toDouble() * v; n++; i += 2
        }
        return if (n == 0) 0.0 else sqrt(sum / n)
    }

    /** RMS after / RMS before, in dB. */
    private fun gainDb(room: VoiceRoom.Room, hz: Double, rate: Int = 44100): Double {
        val n = rate / 4
        val input = FloatArray(n) { (0.3 * sin(2 * PI * hz * it / rate)).toFloat() }
        val stream = VoiceRoom.Stream(rate, room, 100)
        val out = stream.feed(mono(input))
        val before = rms(mono(input))
        val after = rms(out, n)      // вторая половина: фильтр уже устоялся
        return 20 * Math.log10(after / before)
    }

    @Test
    fun aCarMufflesTheHighsAndKeepsTheLows() {
        val car = VoiceRoom.ROOMS.getValue("car")
        val low = gainDb(car, 300.0)
        val high = gainDb(car, 8000.0)
        // Верх гаснет заметно сильнее низа: это и есть «голос из-за стекла».
        assertTrue("низ $low дБ, верх $high дБ", high < low - 6.0)
    }

    @Test
    fun aRoomDoesNotMuffleTheVoice() {
        val room = VoiceRoom.ROOMS.getValue("room")
        val low = gainDb(room, 300.0)
        val high = gainDb(room, 8000.0)
        assertTrue("низ $low дБ, верх $high дБ", high > low - 3.0)
    }

    @Test
    fun aTunnelEchoes() {
        val tunnel = VoiceRoom.ROOMS.getValue("tunnel")
        assertTrue(tunnel.echoMs > 0 && tunnel.echoLevel > 0f)
        assertTrue(VoiceRoom.ROOMS.getValue("stairwell").echoMs > 0)
    }
}
