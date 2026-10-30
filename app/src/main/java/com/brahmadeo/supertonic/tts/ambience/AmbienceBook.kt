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

    /** Variant [k] of a location: 0 — the steady background; then one with each of the place's accents (up to four),
     * again with new seeds. The player takes them at random, so an accent never comes at a fixed interval. */
    fun prompt(location: SceneIndex.Location, k: Int): String {
        if (k <= 0 || location.accents.isEmpty()) return location.sound
        return "${location.sound}, ${location.accents[(k - 1) % location.accents.size]}"
    }

    /** What a location's clips were made from: changes (new prompt or events) make them again. */
    private fun recipe(location: SceneIndex.Location) = (listOf(location.sound) + location.accents).joinToString(" | ")

    private fun manifest(context: Context, sha: String) = File(BookFiles.ambience(context, sha), "sounds.json")
    private fun readManifest(context: Context, sha: String): JSONObject =
        runCatching { JSONObject(manifest(context, sha).readText()) }.getOrDefault(JSONObject())

    /** One clip to make: [file] in the book's ambience folder, from [prompt]; [id]/[recipe] go into sounds.json. */
    class Job(val file: String, val prompt: String, val seconds: Float, val seed: Long, val id: String, val recipe: String, val label: String,
              /** Made by the music model («Музыка по настроению»). */
              val music: Boolean = false)

    /** Длина новых звуков событий — из настроек: клип в базе 8 с, приложение играет выбранные секунды. */
    private fun eventSeconds(context: Context) =
        AmbienceSettings.eventSeconds(context.getSharedPreferences("SupertonicPrefs", 0))

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
        // One clip per kind of action: a door slammed in chapter 1 is the same sound in chapter 30.
        val events = scenes.events.events.groupBy { com.brahmadeo.supertonic.tts.books.EventIndex.fileKey(it) }.map { (key, same) ->
            val prompt = scenes.events.kinds[key.removePrefix("k_")] ?: same.first().sound
            Job("$key.m4a", prompt, eventSeconds(context), key.hashCode().toLong(), key, prompt,
                "событие: " + same.first().kind.ifEmpty { prompt.take(40) })
        }
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

    /** Copies the clips of [job] from the shared bank into the book — all variants of the kind, so the same action
     * sounds different each time (k_door_slam.m4a, k_door_slam_2.m4a, k_door_slam_3.m4a). */
    private fun adoptFromBank(context: Context, dir: File, job: Job, salt: Int = 0): Boolean {
        if (!job.id.startsWith("k_")) return false
        if (!SoundBank.on(context)) return false
        val target = File(dir, job.file)
        if (target.isFile && target.length() > 0L) return true
        val sources = SoundBank.all(context, job.id.removePrefix("k_"), job.prompt)
        var taken = false
        for ((n, source) in sources.withIndex()) {
            val file = if (n == 0) target else File(dir, "${job.id}_${n + 1}.m4a")
            val ok = runCatching { if (!file.isFile || file.length() == 0L) source.copyTo(file, overwrite = true); true }
                .onFailure { Log.w("Ambience", "bank copy ${file.name}: ${it.javaClass.simpleName}") }
                .getOrDefault(false)
            taken = taken || ok
        }
        return taken
    }

    /** l3_0.m4a -> l3; m2_1.m4a -> m2; e5.m4a -> e5; k_door_slam.m4a -> k_door_slam (a clip of a kind of action). */
    private fun idOf(file: File) = when {
        // k_door_slam_2.m4a -> k_door_slam: варианты одного вида чистятся вместе с ним.
        file.name.startsWith("k_") -> file.name.substringBeforeLast('.').removeSuffix("_2").removeSuffix("_3")
        file.name.startsWith("e") -> file.name.substringBefore('.')
        else -> file.name.substringBefore('_')
    }

    /** Drops sounds of places and events that are gone or whose prompt changed. Sounds without a manifest came inside
     * the book file (made for exactly these locations) and are kept. */
    fun sync(context: Context, sha: String, scenes: SceneIndex) {
        val files = BookFiles.sounds(context, sha)
        if (files.isEmpty()) return
        val known = readManifest(context, sha)
        if (known.length() == 0) return
        // База звуков переехала на новую версию (клипы выровнены по громкости): старые копии в книге выкидываем —
        // подготовка возьмёт из базы новые, без генерации.
        val bank = SoundBank.version(context)
        if (SoundBank.on(context) && known.optInt("bank", 1) != bank) {
            val old = files.count { it.name.startsWith("k_") && it.delete() }
            known.put("bank", bank)
            runCatching { manifest(context, sha).writeText(known.toString()) }
            Log.i("Ambience", "база версии $bank: старых звуков событий удалено $old, возьмём новые")
        }
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
        val dir = BookFiles.ambience(context, sha).apply { mkdirs() }
        // «База звуков» first: a clip already made for another book is copied and nothing is generated for it.
        val manifest0 = readManifest(context, sha)
        var reused = 0
        val todo = missing(context, sha, scenes).filter { !it.music || AmbienceModel.musicReady(context) }
            .filter { job ->
                if (job.music || !adoptFromBank(context, dir, job, sha.hashCode())) return@filter true
                manifest0.put(job.id, job.recipe)
                reused++
                false
            }
        if (reused > 0) {
            manifest(context, sha).writeText(manifest0.toString())
            Log.i("Ambience", "$reused sounds taken from the bank, no generation")
        }
        if (todo.isEmpty()) return
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
                    // The NPU graph accepts exactly 323 latents (30 s): music fits, short event sounds do not, and
                    // loading ~1–3 GB of HTP context for them only burns memory and a second per start.
                    npu = if (job.music) AmbienceModel.npuDit(context, music = true,
                        onCompile = { k, n -> progress(done, ordered.size, "подготовка NPU, часть $k из $n (один раз)") })
                        else null
                    generator?.close()
                    val keep = AmbienceSettings.keepModels(context.getSharedPreferences("SupertonicPrefs", 0))
                    generator = if (job.music) AmbienceModel.musicGenerator(context, threads, npu, keep)
                        else Sa3Generator(AmbienceModel.root(context), threads, npu, keep = keep)
                    musicMode = job.music
                }
                val pcm = generator!!.generate(job.prompt, job.seconds, job.seed, cancelled).pcm
                if (job.music && MusicEnhance.enabled(context)) MusicEnhance.apply(pcm, Sa3Generator.SAMPLE_RATE)
                val gain = Loudness.normalize(pcm, Sa3Generator.SAMPLE_RATE, if (job.music) Loudness.MUSIC_LUFS else Loudness.TARGET_LUFS)
                AacFile.write(File(dir, job.file), pcm, bitrate = BITRATE)
                // The bank grows: a sound made for this book is reused by the next one for free.
                if (!job.music && job.id.startsWith("k_") && SoundBank.on(context))
                    SoundBank.store(context, job.id.removePrefix("k_"), File(dir, job.file),
                        custom = !com.brahmadeo.supertonic.tts.books.EventKinds.known(job.id.removePrefix("k_")))
                manifest.put(job.id, job.recipe).put(LUFS_KEY, Loudness.TARGET_LUFS)
                manifest(context, sha).writeText(manifest.toString())
                Log.i("Ambience", "Sound ${job.file} ready (gain ${"%.1f".format(gain)} dB, npu=${npu != null && job.seconds == SECONDS}, music=${job.music})")
            }
            progress(ordered.size, ordered.size, "")
        } finally { generator?.close(); npu?.close() }
    }
}
