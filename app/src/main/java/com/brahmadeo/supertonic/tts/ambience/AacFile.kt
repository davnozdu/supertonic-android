package com.brahmadeo.supertonic.tts.ambience

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.math.roundToInt

/** Compact storage for generated ambience: AAC-LC in .m4a through the platform encoder (Android has no MP3 encoder);
 * MediaPlayer plays it the same way as the bundled MP3 music. */
object AacFile {
    fun write(file: File, pcm: FloatArray, sampleRate: Int = Sa3Generator.SAMPLE_RATE, bitrate: Int = 96_000) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val tmp = File(file.path + ".part").apply { delete() }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            val info = MediaCodec.BufferInfo()
            var track = -1; var frame = 0; var inputDone = false
            val frames = pcm.size / 2
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!.apply { clear(); order(java.nio.ByteOrder.nativeOrder()) }
                        val n = minOf(frames - frame, buffer.capacity() / 4)
                        for (k in 0 until n * 2) buffer.putShort((pcm[frame * 2 + k].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
                        val time = frame * 1_000_000L / sampleRate
                        frame += n
                        inputDone = frame >= frames
                        codec.queueInputBuffer(index, 0, n * 4, time, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                    out >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0)
                            muxer.writeSampleData(track, codec.getOutputBuffer(out)!!, info)
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            muxer.stop()
        } finally {
            runCatching { codec.stop() }; codec.release(); runCatching { muxer.release() }
        }
        check(tmp.renameTo(file)) { "rename ${tmp.path}" }
    }
}
