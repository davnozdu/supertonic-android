package com.brahmadeo.supertonic.tts.ambience

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.nio.ByteOrder

/** Bounded platform decoding: short events or complete ambience <=90s, stereo preserved. */
internal object FreesoundAudio {
    fun prepare(source: File, target: File, event: Boolean, duration: Double, music: Boolean = false, clipSeconds: Int? = null, cancelled: () -> Boolean) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(source.path)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(rate in 8000..48000 && channels in 1..2)
            val limit = if (event) 31 else 91
            require(duration > 0 && duration <= limit - 1)
            val maxFrames = ((clipSeconds?.toDouble() ?: minOf(duration + 0.75, limit.toDouble())) * rate).toInt()
            val runtime = Runtime.getRuntime()
            val free = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
            require(maxFrames.toLong() * 2 * 4 * 8 < free) { "Insufficient audio preparation memory" }
            val pcm = FloatArray(maxFrames * 2)
            var frames = 0
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0); codec.start()
                var inputDone = false; var complete = false; var encoding = AudioFormat.ENCODING_PCM_16BIT
                val info = MediaCodec.BufferInfo(); val deadline = SystemClock.elapsedRealtime() + 30_000
                while (!complete && SystemClock.elapsedRealtime() < deadline) {
                    if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val count = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (count < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, count, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        require(f.getInteger(MediaFormat.KEY_SAMPLE_RATE) == rate && f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == channels)
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT)
                    } else if (o >= 0) {
                        try {
                            val buf = codec.getOutputBuffer(o)!!.duplicate().order(ByteOrder.nativeOrder())
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val frameBytes = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            while (buf.remaining() >= frameBytes && (clipSeconds == null || frames < maxFrames)) {
                                require(frames < maxFrames) { "Preview exceeds declared duration" }
                                fun sample() = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buf.float else buf.short / 32768f
                                val left = sample(); val right = if (channels == 1) left else sample()
                                require(left.isFinite() && right.isFinite())
                                pcm[frames * 2] = left; pcm[frames * 2 + 1] = right; frames++
                            }
                        } finally { codec.releaseOutputBuffer(o, false) }
                        complete = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || (clipSeconds != null && frames >= maxFrames)
                    }
                }
                check(complete && frames >= rate * 0.4)
            } finally { runCatching { codec.stop() }; codec.release() }
            var samples = pcm.copyOf(frames * 2)
            if (event && !music) samples = EventWindow.crop(samples, rate)
            check(samples.any { kotlin.math.abs(it) > 0.0001f }) { "Silent recording" }
            Loudness.normalize(samples, rate, if (music) Loudness.MUSIC_LUFS else if (event) -22.0 else Loudness.TARGET_LUFS, maxPeakDb = if (music) Loudness.MUSIC_PEAK_DB else -3.0)
            val fade = minOf(rate / 100, samples.size / 4)
            for (i in 0 until fade) for (ch in 0..1) {
                val g = i.toFloat() / fade
                samples[i * 2 + ch] *= g; samples[samples.size - 2 - i * 2 + ch] *= g
            }
            if (cancelled()) throw InterruptedException("cancelled")
            AacFile.write(target, samples, rate, if (event || music) 128_000 else 96_000)
        } finally { ex.release() }
    }
}
