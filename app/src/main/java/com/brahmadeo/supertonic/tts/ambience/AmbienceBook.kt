package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import android.util.Log
import com.brahmadeo.supertonic.tts.books.BookFiles
import com.brahmadeo.supertonic.tts.books.SceneIndex
import org.json.JSONObject
import java.io.File

/** The sounds of one book's locations: [variants] clips of [SECONDS] s per location (the player plays them in a random
 * order with crossfades; variants with the place's events make it lively), loudness-normalised to [Loudness.TARGET_LUFS], AAC 64 kbit/s in BookFiles.ambience. sounds.json records
 * the prompt each location was made from, so a re-prepared book regenerates only the places that changed. */
object AmbienceBook {
    const val SECONDS = 30f
    private const val BITRATE = 64_000
    private const val LUFS_KEY = "_lufs"

    private const val MAX_CLIPS = 240

    /** The chosen number of variants, fewer when the book has so many locations that the clips would pass [MAX_CLIPS]. */
    fun variants(context: Context, locations: Int): Int {
        val wanted = AmbienceSettings.variants(context.getSharedPreferences("SupertonicPrefs", 0))
        return if (locations == 0) wanted else minOf(wanted, maxOf(2, MAX_CLIPS / locations))
    }

    /** Variant [k] of a location: 0 — steady background; 1, 2 — with the first / second event; then the same again
     * with other seeds. A place without events gets the background each time. */
    fun prompt(location: SceneIndex.Location, k: Int): String =
        location.accents.getOrNull(k % 3 - 1)?.let { "${location.sound}, $it" } ?: location.sound

    /** What a location's clips were made from: changes (new prompt or events) make them again. */
    private fun recipe(location: SceneIndex.Location) = (listOf(location.sound) + location.accents).joinToString(" | ")

    private fun manifest(context: Context, sha: String) = File(BookFiles.ambience(context, sha), "sounds.json")
    private fun readManifest(context: Context, sha: String): JSONObject =
        runCatching { JSONObject(manifest(context, sha).readText()) }.getOrDefault(JSONObject())

    /** One clip to make: [file] in the book's ambience folder, from [prompt]; [id]/[recipe] go into sounds.json. */
    class Job(val file: String, val prompt: String, val seconds: Float, val seed: Long, val id: String, val recipe: String, val label: String,
              /** Made by the music model («Музыка по настроению»). */
              val music: Boolean = false)

    /** Music fragments per mood, played in a random order like the scene variants. */
    private const val MUSIC_VARIANTS = 2

    /** Event sounds are short one-shots; the chapter transition a little shorter. */
    const val EVENT_SECONDS = 8f
    private const val CHAPTER_SECONDS = 3f

    /** Every clip the book needs: its locations' variants, its events and the chapter transition. */
    private fun jobs(context: Context, scenes: SceneIndex): List<Job> {
        val n = variants(context, scenes.locations.size)
        val places = scenes.locations.flatMap { loc -> (0 until n).map { k ->
            Job("${loc.id}_$k.m4a", prompt(loc, k), SECONDS, loc.sound.hashCode().toLong() * 31 + k, loc.id, recipe(loc), loc.name)
        } }
        val events = scenes.events.events.map { e -> Job("${e.id}.m4a", e.sound, EVENT_SECONDS, e.sound.hashCode().toLong(), e.id,
            "${e.sound} | ${EVENT_SECONDS.toInt()} s", "событие") }
        val chapter = com.brahmadeo.supertonic.tts.books.EventIndex.CHAPTER_ID.let { id ->
            Job("$id.m4a", com.brahmadeo.supertonic.tts.books.EventIndex.CHAPTER_SOUND, CHAPTER_SECONDS, 1L, id,
                com.brahmadeo.supertonic.tts.books.EventIndex.CHAPTER_SOUND, "переход главы")
        }
        val music = if (!AmbienceSettings.moodMusic(context.getSharedPreferences("SupertonicPrefs", 0))) emptyList()
            else scenes.moods.moods.flatMap { m -> (0 until MUSIC_VARIANTS).map { k ->
                Job("${m.id}_$k.m4a", MusicEnhance.prompt(m.music), SECONDS, m.music.hashCode().toLong() * 31 + k, m.id, m.music, "музыка: ${m.name}", music = true)
            } }
        return places + events + chapter + music
    }

    /** l3_0.m4a -> l3; m2_1.m4a -> m2; e5.m4a -> e5. */
    private fun idOf(file: File) = if (file.name.startsWith("e")) file.name.substringBefore('.') else file.name.substringBefore('_')

    /** Drops sounds of places and events that are gone or whose prompt changed. Sounds without a manifest came inside
     * the book file (made for exactly these locations) and are kept. */
    fun sync(context: Context, sha: String, scenes: SceneIndex) {
        val files = BookFiles.sounds(context, sha)
        if (files.isEmpty()) return
        val known = readManifest(context, sha)
        if (known.length() == 0) return
        // Made at another loudness (before −14 LUFS): all of them again.
        if (known.optDouble(LUFS_KEY, -20.0) != Loudness.TARGET_LUFS) { files.forEach { it.delete() }; manifest(context, sha).delete(); return }
        val current = jobs(context, scenes).associate { it.id to it.recipe }
        for (f in files) {
            val id = idOf(f)
            // Mood music stays when the switch is only turned off (it would be made again on).
            if (id.startsWith("m") && current[id] == null && scenes.moods.moods.any { it.id == id && it.music == known.optString(id) }) continue
            if (current[id] == null || known.optString(id) != current[id]) f.delete()
        }
    }

    /** Sounds that came inside a book file were made for exactly its locations and events: record that, so a later
     * re-preparation (new numbers) drops them instead of playing them in the wrong places. */
    fun adopt(context: Context, sha: String, scenes: SceneIndex) {
        if (BookFiles.sounds(context, sha).isEmpty()) return
        val manifest = JSONObject().put(LUFS_KEY, Loudness.TARGET_LUFS)
        for (job in jobs(context, scenes)) manifest.put(job.id, job.recipe)
        runCatching { manifest(context, sha).writeText(manifest.toString()) }
    }

    /** Clips still to make. */
    fun missing(context: Context, sha: String, scenes: SceneIndex): List<Job> {
        sync(context, sha, scenes)
        val dir = BookFiles.ambience(context, sha)
        return if (scenes.locations.isEmpty() && scenes.events.events.isEmpty()) emptyList()
            else jobs(context, scenes).filter { !File(dir, it.file).isFile }
    }

    /** Generates the missing clips (one at a time; the 30 s ones on the NPU when switched on). [progress]: (done, total, place). */
    fun generate(context: Context, sha: String, scenes: SceneIndex, threads: Int = 4,
                 progress: (Int, Int, String) -> Unit = { _, _, _ -> }, cancelled: () -> Boolean = { false }) {
        if (AmbienceModel.busy) progress(0, 0, "ждёт окончания другой работы генератора")
        AmbienceModel.exclusive { generateLocked(context, sha, scenes, threads, progress, cancelled) }
    }

    private fun generateLocked(context: Context, sha: String, scenes: SceneIndex, threads: Int,
                               progress: (Int, Int, String) -> Unit, cancelled: () -> Boolean) {
        require(AmbienceModel.ready(context)) { "Скачайте генератор фоновых звуков в разделе «Фоновая музыка»" }
        // Music without its model (not downloaded yet) waits for it; the sounds are made now.
        val todo = missing(context, sha, scenes).filter { !it.music || AmbienceModel.musicReady(context) }
        if (todo.isEmpty()) return
        val dir = BookFiles.ambience(context, sha).apply { mkdirs() }
        // One NPU session at a time: the sounds first, then the music (each ~0.9 GB of HTP context).
        val ordered = todo.sortedBy { it.music }
        var npu: Sa3Generator.NpuDit? = null
        var generator: Sa3Generator? = null
        var musicMode: Boolean? = null
        try {
            val manifest = readManifest(context, sha)
            for ((done, job) in ordered.withIndex()) {
                if (cancelled()) throw InterruptedException("cancelled")
                progress(done, ordered.size, job.label)
                if (musicMode != job.music) {
                    npu?.close()
                    npu = AmbienceModel.npuDit(context, music = job.music,
                        onCompile = { k, n -> progress(done, ordered.size, "подготовка NPU, часть $k из $n (один раз)") })
                    generator = if (job.music) AmbienceModel.musicGenerator(context, threads, npu) else Sa3Generator(AmbienceModel.root(context), threads, npu)
                    musicMode = job.music
                }
                val pcm = generator!!.generate(job.prompt, job.seconds, job.seed, cancelled).pcm
                if (job.music && MusicEnhance.enabled(context)) MusicEnhance.apply(pcm, Sa3Generator.SAMPLE_RATE)
                val gain = Loudness.normalize(pcm, Sa3Generator.SAMPLE_RATE, if (job.music) Loudness.MUSIC_LUFS else Loudness.TARGET_LUFS)
                AacFile.write(File(dir, job.file), pcm, bitrate = BITRATE)
                manifest.put(job.id, job.recipe).put(LUFS_KEY, Loudness.TARGET_LUFS)
                manifest(context, sha).writeText(manifest.toString())
                Log.i("Ambience", "Sound ${job.file} ready (gain ${"%.1f".format(gain)} dB, npu=${npu != null && job.seconds == SECONDS}, music=${job.music})")
            }
            progress(ordered.size, ordered.size, "")
        } finally { npu?.close() }
    }
}
