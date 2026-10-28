package com.brahmadeo.supertonic.tts.music

import android.content.Context
import com.brahmadeo.supertonic.tts.ambience.AacFile
import com.brahmadeo.supertonic.tts.ambience.AmbienceModel
import com.brahmadeo.supertonic.tts.ambience.Loudness
import com.brahmadeo.supertonic.tts.ambience.Sa3Generator
import org.json.JSONObject
import java.io.File

/** «Своя музыка»: background tracks made on the phone from a description (Stable Audio 3 Small Music), for anyone who
 * cannot prepare a book. Each is a seamless loop (its end crossfades into its start), kept as files/music-generated/
 * <id>.m4a with <id>.json (title, description, English prompt); chosen in «Фоновая музыка» as "gen:<id>". */
object GeneratedMusic {
    data class Track(val id: String, val title: String, val description: String, val prompt: String, val seconds: Int, val file: File)

    private const val LOOP_FADE_S = 2f
    private val ID = Regex("[0-9]{13}")

    private fun dir(context: Context) = File(context.filesDir, "music-generated")

    fun list(context: Context): List<Track> = dir(context).listFiles()?.filter { it.name.endsWith(".json") && ID.matches(it.nameWithoutExtension) }
        ?.mapNotNull { meta -> runCatching {
            val o = JSONObject(meta.readText()); val audio = File(meta.parentFile, meta.nameWithoutExtension + ".m4a")
            if (!audio.isFile) null else Track(meta.nameWithoutExtension, o.getString("title"), o.optString("description"), o.optString("prompt"), o.optInt("seconds"), audio)
        }.getOrNull() }?.sortedByDescending { it.id }.orEmpty()

    fun file(context: Context, id: String): File? = id.takeIf { ID.matches(it) }?.let { File(dir(context), "$it.m4a") }?.takeIf { it.isFile }

    fun remove(context: Context, id: String) {
        if (!ID.matches(id)) return
        File(dir(context), "$id.m4a").delete(); File(dir(context), "$id.json").delete()
        val prefs = context.getSharedPreferences("SupertonicPrefs", 0)
        if (prefs.getString("background_music_track", "") == "gen:$id") prefs.edit().remove("background_music_track").apply()
    }

    /** Makes a track: translates [description] if needed (cloud → Gemma 4), generates [seconds] of music (30 s on the
     * NPU when it is on, longer on the CPU), loudness to −14 LUFS, a seamless loop (2 s shorter), AAC 128 kbit/s. */
    fun create(context: Context, description: String, seconds: Int, progress: (String) -> Unit, cancelled: () -> Boolean): Track {
        require(AmbienceModel.musicReady(context)) { "Скачайте модель музыки" }
        progress(if (com.brahmadeo.supertonic.tts.ambience.MusicPrompt.cyrillic(description)) "Перевод описания" else "Подготовка")
        val prompt = com.brahmadeo.supertonic.tts.ambience.MusicPrompt.english(context, description)
        if (cancelled()) throw InterruptedException("cancelled")
        progress("Создание музыки (${seconds} с)")
        val npu = if (seconds == 30) AmbienceModel.npuDit(context, music = true) else null
        val pcm = try {
            Sa3Generator(AmbienceModel.root(context), 4, npu, "dit_music_w8a8.tflite")
                .generate(prompt, seconds.toFloat(), System.currentTimeMillis(), cancelled).pcm
        } finally { npu?.close() }
        progress("Сохранение")
        Loudness.normalize(pcm, Sa3Generator.SAMPLE_RATE)
        val loop = loop(pcm, (LOOP_FADE_S * Sa3Generator.SAMPLE_RATE).toInt())
        val id = System.currentTimeMillis().toString()
        val folder = dir(context).apply { mkdirs() }
        AacFile.write(File(folder, "$id.m4a"), loop, bitrate = 128_000)
        val title = description.trim().replace(Regex("\\s+"), " ").take(48)
        File(folder, "$id.json").writeText(JSONObject().put("title", title).put("description", description.trim()).put("prompt", prompt).put("seconds", seconds).toString())
        return Track(id, title, description.trim(), prompt, seconds, File(folder, "$id.m4a"))
    }

    /** Interleaved stereo [pcm] made loopable: its last [fade] frames are crossfaded (equal power) into its first ones,
     * and the result starts where that fade ends — played in a loop, the end flows into the start without a seam. */
    fun loop(pcm: FloatArray, fade: Int): FloatArray {
        val frames = pcm.size / 2
        if (frames <= fade * 2) return pcm
        val out = FloatArray((frames - fade) * 2)
        val body = frames - 2 * fade
        System.arraycopy(pcm, fade * 2, out, 0, body * 2)
        for (j in 0 until fade) {
            val t = (j + 0.5f) / fade
            val gOut = kotlin.math.cos(t * Math.PI / 2).toFloat(); val gIn = kotlin.math.sin(t * Math.PI / 2).toFloat()
            for (c in 0..1) out[(body + j) * 2 + c] = pcm[(frames - fade + j) * 2 + c] * gOut + pcm[j * 2 + c] * gIn
        }
        return out
    }
}
