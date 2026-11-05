package com.brahmadeo.supertonic.tts.pocket

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.brahmadeo.supertonic.tts.tera.TeraCustomVoices
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The user's own Shtorm (Pocket TTS) voices: a speech recording (wav, mp3, m4a, ogg… — whatever Android decodes),
 * cleaned by [PocketVoicePrep] (idea and steps from the TeraTTS fork 1.24–1.24.1, VoiceImport.kt): overloaded peaks
 * drawn round, rumble cut, long hesitations shortened, 6–10 s of speech taken, levelled. Stored as
 * shtorm-pocket-v2/custom/my_<name>_<hash>.wav (24 kHz mono PCM16): the hash of the voice's own sound keeps the
 * engine's voice caches (keyed by the file name) and the audio caches (keyed by the path) from mixing two timbres.
 * The piece of the recording is chosen before saving (in the import dialog), so a saved voice never changes.
 */
object PocketCustomVoices {
    const val PREFIX = TeraCustomVoices.PREFIX
    private const val RATE = PocketVoicePrep.RATE

    class Failure(message: String) : Exception(message)

    fun dir(context: Context) = File(PocketDownload.root(context), "custom")
    fun isCustom(name: String) = name.startsWith(PREFIX)
    fun file(context: Context, name: String) = File(dir(context), "$name.wav")

    fun installed(context: Context): List<String> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".wav") && isCustom(f.name) && f.length() > 44 + RATE * 2 }
            .orEmpty().map { it.nameWithoutExtension }.sorted()

    fun delete(context: Context, name: String) {
        require(isCustom(name) && '/' !in name && ".." !in name)
        file(context, name).delete()
        // the engine's conditioning caches of this file (voices dir: the model's folder or its int8/, fast or slow)
        val root = PocketDownload.root(context)
        listOf(root, File(root, "int8"), PocketDownload.slowRoot(context), File(PocketDownload.slowRoot(context), "int8")).forEach { d ->
            File(d, ".cache").listFiles { f -> f.name.startsWith("$name.") }?.forEach { it.delete() }
        }
    }

    /** A decoded, cleaned recording waiting in the import dialog: [x] 24 kHz mono, before a piece is taken. */
    class Draft(val title: String, val x: FloatArray, val report: PocketVoicePrep.Report)

    /** What the user should know about the recording (overload, muffled, noise, too little speech). */
    fun notes(draft: Draft, piece: FloatArray?): List<String> = buildList {
        if (draft.report.clipped) add("В записи перегруз (вершины звука срезаны). Они сглажены, но лучше записать тише или подальше от микрофона.")
        if (draft.report.muffled) add("Запись глухая, как голосовое из мессенджера или звонок — голос будет глуховатым. Лучше записать обычным диктофоном.")
        if (draft.report.noisy) add("Слышен фоновый шум (голос громче него всего на ${draft.report.snrDb.toInt()} дБ) — Shtorm перенесёт его в чтение. Лучше записать в тишине.")
        if (piece != null && PocketVoicePrep.seconds(piece) < 6f) add("Чистой речи меньше 6 секунд — лучше 6–10 секунд законченными фразами.")
    }

    /** Decodes [uri] (up to 2 minutes), repairs overload, checks and cleans it. Throws [Failure]. */
    fun analyse(context: Context, uri: Uri): Draft {
        val d = decode(context, uri)
        if (d.x.size < RATE) throw Failure("запись короче секунды")
        val report = PocketVoicePrep.check(d.x, d.rate, d.clipRuns)
        return Draft(titleOf(context, uri), PocketVoicePrep.clean(d.x), report)
    }

    /** The voice: 6–10 s of the draft by [part] ([PocketVoicePrep.PART_AUTO] — the cleanest). Throws [Failure]. */
    fun piece(draft: Draft, part: Int): FloatArray =
        try { PocketVoicePrep.pick(draft.x, part) } catch (e: PocketVoicePrep.Failure) { throw Failure(e.message ?: "запись не подходит") }

    fun pcm16(x: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putShort((it.coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
        return b.array()
    }

    /** Saves [clip] as a voice; returns its name. */
    fun save(context: Context, draft: Draft, clip: FloatArray): String {
        val pcm = pcm16(clip)
        val hash = MessageDigest.getInstance("SHA-256").digest(pcm).take(3).joinToString("") { "%02x".format(it.toInt() and 255) }
        val clean = draft.title.replace(Regex("""[^\p{L}\p{N}]+"""), "_").trim('_').removePrefix(PREFIX).take(32).ifEmpty { "voice" }
        val name = "$PREFIX${clean}_$hash"
        val dir = dir(context).apply { mkdirs() }
        val temporary = File(dir, ".import-$name.wav")
        temporary.writeBytes(wavHeader(pcm.size) + pcm)
        val target = file(context, name)
        target.delete() // same hash = same sound
        if (!temporary.renameTo(target)) { temporary.delete(); throw Failure("не удалось сохранить голос") }
        return name
    }

    private fun wavHeader(bytes: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + bytes); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(bytes)
    }.array()

    private fun titleOf(context: Context, uri: Uri): String {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { null } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "voice"
        return name.substringBeforeLast('.').takeIf { Regex("""\p{L}""").containsMatchIn(it) } ?: "voice"
    }

    /** A decoded recording: 24 kHz mono, its own rate, how many overloaded runs were drawn round. */
    private class Decoded(val x: FloatArray, val rate: Int, val clipRuns: Int)

    /** A recording → mono 24 kHz with Android's own decoders (anything the phone can play); the first 2 minutes. */
    private fun decode(context: Context, uri: Uri): Decoded {
        val ex = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { ex.setDataSource(it.fileDescriptor) } ?: throw Failure("файл не читается")
        } catch (e: Failure) { ex.release(); throw e } catch (e: Exception) { ex.release(); throw Failure("файл не читается как звук") }
        try {
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw Failure("в файле нет звука")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (rate !in 8000..192000 || channels !in 1..8) throw Failure("неподдерживаемая частота или число каналов записи")
            val codec = try { MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!) } catch (e: Exception) { throw Failure("формат записи не поддерживается") }
            val runtime = Runtime.getRuntime()
            val freeHeap = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            // Leave room for the block collector, contiguous mono, declipping, resampling and analysis/cleaning.
            val maxSamples = minOf(PocketRecordingPcm.MAX_SAMPLES.toLong(), freeHeap / 32).toInt()
            var pcm: PocketRecordingPcm? = null
            fun output(format: MediaFormat): PocketRecordingPcm {
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT)
                    throw Failure("формат PCM записи не поддерживается; сохраните её как обычный WAV или MP3")
                return PocketRecordingPcm(sampleRate, channelCount, encoding == AudioFormat.ENCODING_PCM_FLOAT, maxSamples)
            }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
                var complete = false
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("voice import cancelled")
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val newChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (pcm != null && (newRate != rate || newChannels != channels)) throw Failure("формат звука изменился посреди записи")
                        rate = newRate; channels = newChannels
                        if (pcm == null) pcm = output(f)
                    } else if (o >= 0) {
                        try {
                            if (pcm == null) pcm = output(codec.outputFormat)
                            if (info.size > 0) {
                                val buf = codec.getOutputBuffer(o)!!.duplicate()
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                pcm!!.append(buf)
                            }
                        } finally { codec.releaseOutputBuffer(o, false) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || pcm!!.full) { complete = true; break }
                    }
                }
                if (!complete) throw Failure("декодирование записи заняло слишком долго; выберите более короткий фрагмент")
            } catch (e: PocketRecordingPcm.Failure) {
                throw Failure(e.message ?: "запись слишком большая")
            } finally {
                runCatching { codec.stop() }; codec.release()
            }
            val decoded = pcm ?: throw Failure("в записи нет звуковых кадров")
            rate = decoded.rate
            val mono = decoded.samples()
            // overloaded peaks drawn round at the recording's own rate, before resampling smears the flat tops
            val (fixed, runs) = PocketVoicePrep.declip(mono)
            return Decoded(PocketVoicePrep.resample(fixed, rate, RATE), rate, runs)
        } finally {
            ex.release()
        }
    }
}
