package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import com.brahmadeo.supertonic.tts.books.BookLibrary
import com.brahmadeo.supertonic.tts.books.BookMatcher
import com.brahmadeo.supertonic.tts.books.SceneIndex

/** «Акустика помещения для голоса»: a light reverb on the synthesized speech by the kind of place of the scene (the LLM
 * names it with the setting): dry outdoors, a short small room, a long hall or cave. Freeverb (8 combs + 4 all-passes)
 * on 16-bit mono at the output rate, a few operations per sample; mixed low (8–18 %), so it colours, never blurs.
 * Off by default (own switch); the room is part of the audio cache key. No tail is added: pauses stay as they are. */
object VoiceRoom {
    const val KEY = "ambience_room"
    /** [size]: comb feedback (decay); [damp]: high-frequency loss; [wet]: share of the reverb in the mix. */
    data class Room(val name: String, val size: Float, val damp: Float, val wet: Float)

    val ROOMS = mapOf(
        "room" to Room("room", 0.70f, 0.45f, 0.08f),
        "large_room" to Room("large_room", 0.80f, 0.40f, 0.11f),
        "hall" to Room("hall", 0.88f, 0.30f, 0.15f),
        "vehicle" to Room("vehicle", 0.60f, 0.60f, 0.06f),
        "cave" to Room("cave", 0.92f, 0.20f, 0.18f),
    )

    fun enabled(context: Context) = context.getSharedPreferences("SupertonicPrefs", 0).getBoolean(KEY, false)

    @Volatile private var last: Room? = null

    /** The room of [text] in the book being read (its scene's acoustics), the previous one for a piece without a hit,
     * null outdoors, outside scenes or with the switch off. */
    fun forText(context: Context, text: String): Room? {
        if (!enabled(context)) return null
        val book = BookMatcher.recognised.value?.book ?: return null.also { last = null }
        val scenes = runCatching { BookLibrary.scenes(context, book) }.getOrNull() ?: return null
        return when (val found = scenes.find(text, emptyList())) {
            is SceneIndex.At -> ROOMS[found.location.acoustics].also { last = it }
            SceneIndex.Outside -> null.also { last = null }
            SceneIndex.Unknown -> last
        }
    }

    /** Streaming reverb of 16-bit little-endian mono PCM at [rate]. */
    class Stream(rate: Int, private val room: Room) {
        private val scale = rate / 44100f
        private val combs = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { Comb((it * scale).toInt().coerceAtLeast(8)) }
        private val passes = intArrayOf(556, 441, 341, 225).map { AllPass((it * scale).toInt().coerceAtLeast(4)) }
        private var odd: Byte? = null

        private inner class Comb(n: Int) {
            val buf = FloatArray(n); var i = 0; var store = 0f
            fun run(x: Float): Float {
                val y = buf[i]
                store = y * (1 - room.damp) + store * room.damp
                buf[i] = x + store * room.size
                if (++i == buf.size) i = 0
                return y
            }
        }
        private class AllPass(n: Int) {
            val buf = FloatArray(n); var i = 0
            fun run(x: Float): Float {
                val b = buf[i]; val y = -x + b
                buf[i] = x + b * 0.5f
                if (++i == buf.size) i = 0
                return y
            }
        }

        fun feed(bytes: ByteArray): ByteArray {
            val data = odd?.let { byteArrayOf(it) + bytes } ?: bytes
            val n = data.size / 2
            odd = if (data.size % 2 == 1) data.last() else null
            val out = ByteArray(n * 2)
            for (k in 0 until n) {
                val x = ((data[2 * k].toInt() and 255) or (data[2 * k + 1].toInt() shl 8)).toShort() / 32768f
                var wet = 0f
                for (c in combs) wet += c.run(x * 0.015f)
                for (a in passes) wet = a.run(wet)
                val y = (x * (1 - room.wet) + wet * 3f * room.wet).coerceIn(-1f, 1f)
                val s = (y * 32767f).toInt()
                out[2 * k] = s.toByte(); out[2 * k + 1] = (s shr 8).toByte()
            }
            return out
        }
    }
}
