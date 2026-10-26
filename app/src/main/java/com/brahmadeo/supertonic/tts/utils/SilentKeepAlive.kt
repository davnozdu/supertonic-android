package com.brahmadeo.supertonic.tts.utils

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Long background work on Huawei / Honor (model downloads, book preparation): with the screen off
 * or the app in the background the system freezes the app (foreground service and wake lock or
 * not) unless it is playing sound. While such work runs, a silent track plays (zeros, 8 kHz mono:
 * no sound, next to no work, no audio focus taken — music in another app is not interrupted).
 * Other phones need none. From the TeraTTS fork 1.6.9 (github.com/9208499-ship-it/teratts-android,
 * GPL-3.0), where it kept saving to a file going. Nested work counts: the track stops with the last.
 */
object SilentKeepAlive {
    private const val TAG = "SilentKeepAlive"
    @Volatile private var track: AudioTrack? = null
    @Volatile private var thread: Thread? = null
    private var users = 0

    fun needed(): Boolean = Build.MANUFACTURER.lowercase().let { it.contains("huawei") || it.contains("honor") }

    @Synchronized
    fun start() {
        if (!needed()) return
        users++
        if (track != null) return
        try {
            val rate = 8000
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, rate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t
            val silence = ByteArray(rate / 5) // 0.1 s
            thread = Thread {
                try {
                    while (track === t) {
                        if (t.write(silence, 0, silence.size) < 0) break
                    }
                } catch (_: Throwable) { }
            }.apply { name = "SilentKeepAlive"; isDaemon = true; start() }
            Log.i(TAG, "silent track on during background work")
        } catch (e: Throwable) {
            Log.w(TAG, "silent track failed: ${e.message}")
            track = null
        }
    }

    @Synchronized
    fun stop() {
        if (!needed()) return
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        val t = track ?: return
        track = null
        try { t.pause(); t.flush(); t.stop() } catch (_: Throwable) { }
        try { thread?.join(500) } catch (_: Throwable) { }
        thread = null
        try { t.release() } catch (_: Throwable) { }
    }
}
