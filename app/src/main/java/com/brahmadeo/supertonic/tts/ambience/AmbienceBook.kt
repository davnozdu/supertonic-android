package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import android.os.Process
import android.os.SystemClock
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
    /** Версия обработки музыки: 2 — пик не выше −1 dBFS вместо ограничителя. */
    private const val MUSIC_KEY = "_music"
    private const val MUSIC_VERSION = 2

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
              val music: Boolean = false) {
        /** Звук события или перехода главы (не фон локации и не музыка). */
        val event: Boolean get() = !music && (id.startsWith("k_") || id.startsWith("e"))
    }

    /** Music fragments per mood, played in a random order like the scene variants. */
    /** Три фрагмента на настроение (было два): книга читается около часа, а фрагмент — 30 с; с двумя вариантами
     * одно и то же возвращается слишком часто. */
    private const val MUSIC_VARIANTS = 3

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
            // Всегда 8 с, как клипы базы: ползунок «Длина звука события» обрезает при воспроизведении, и его можно
            // поднять позже без новой генерации.
            Job("$key.m4a", prompt, EVENT_SECONDS, key.hashCode().toLong(), key, prompt,
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
        if (!known.has("bank")) {
            // Книги до этой версии отметки не хранили (и очистка их звуков не видела): берём их клипы как есть.
            known.put("bank", bank)
            runCatching { manifest(context, sha).writeText(known.toString()) }
        } else if (SoundBank.on(context) && known.optInt("bank", 1) != bank) {
            val old = files.count { it.name.startsWith("k_") && it.delete() }
            known.put("bank", bank)
            // Сразу берём новые клипы из базы: иначе до «Создать фоновые звуки» события книги молчали бы.
            val dir = BookFiles.ambience(context, sha)
            val again = jobs(context, scenes).count { it.id.startsWith("k_") && adoptFromBank(context, dir, it) }
            runCatching { manifest(context, sha).writeText(known.toString()) }
            Log.i("Ambience", "база версии $bank: старых звуков событий удалено $old, из новой базы взято $again")
        }
        // Музыка, выровненная прежним способом (пики в ограничителе — хрип на громких нотах), делается заново один раз.
        if (known.optInt(MUSIC_KEY, 1) != MUSIC_VERSION) {
            files.filter { it.name.startsWith("m") }.forEach { it.delete() }
            known.put(MUSIC_KEY, MUSIC_VERSION)
            runCatching { manifest(context, sha).writeText(known.toString()) }
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
        // Звуки событий пришли вместе с книгой: помечаем их текущей версией базы, иначе sync() сочтёт их устаревшими и
        // удалит до следующего «Создать фоновые звуки».
        val manifest = JSONObject().put(LUFS_KEY, Loudness.TARGET_LUFS).put("bank", SoundBank.version(context)).put(MUSIC_KEY, MUSIC_VERSION)
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
        // Версия базы, из которой взяты звуки событий: без неё sync() принял бы их за устаревшие и удалил.
        val manifest0 = readManifest(context, sha).put("bank", SoundBank.version(context)).put(MUSIC_KEY, MUSIC_VERSION)
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
        // Один генератор и одна NPU-сессия на пачку подряд идущих клипов одной модели (малая, средняя для событий,
        // музыка) и одного места счёта DiT.
        val prefs = context.getSharedPreferences("SupertonicPrefs", 0)
        val ordered = todo.sortedBy { genOf(context, it).ordinal }
        val place = HashMap<Job, Int>().apply { ordered.forEachIndexed { i, job -> put(job, i) } }
        var npu: Sa3Generator.NpuDit? = null
        var generator: Sa3Generator? = null
        var mode: Gen? = null
        // Сбой NPU посреди книги (QNN 1007 «SSR detected» — перезапуск CDSP) не отключает NPU до конца книги:
        // остаток пачки считает CPU, а следующая пачка пробует NPU снова после паузы (как у Kokoro и Tera).
        var npuFailures = 0
        var npuRetryAt = 0L
        try {
            val doc = readManifest(context, sha).put("bank", SoundBank.version(context)).put(MUSIC_KEY, MUSIC_VERSION)
            for (batch in batches(context, ordered)) {
                if (cancelled()) throw InterruptedException("cancelled")
                if (mode != batch.gen) {
                    generator?.npuDecoder?.close(); generator?.close(); npu?.close(); npu = null
                    val keep = AmbienceSettings.keepModels(prefs)
                    generator = when (batch.gen) {
                        Gen.MUSIC -> AmbienceModel.musicGenerator(context, threads, null, keep).also {
                            // «Большой декодер» на NPU: весь 30-секундный фрагмент одним проходом вместо ~10 с на CPU.
                            it.npuDecoder = AmbienceModel.largeDecoderNpu(context, onCompile = {
                                progress(place[batch.jobs.first()] ?: 0, ordered.size, "подготовка NPU: большой декодер (один раз)") })
                        }
                        Gen.MEDIUM_SFX -> AmbienceModel.mediumSfxGenerator(context, threads, keep)
                        Gen.SMALL -> Sa3Generator(AmbienceModel.root(context), threads, null, keep = keep)
                    }
                    mode = batch.gen
                }
                // HTP-сессия живёт только пока делаются клипы, которые идут на NPU; 1–3 ГБ на прочие не держим.
                if (!batch.npu && npu != null) { npu!!.close(); npu = null }
                if (batch.npu && npu == null && SystemClock.elapsedRealtime() >= npuRetryAt) npu = AmbienceModel.npuDit(context,
                    music = batch.gen != Gen.SMALL, model = if (batch.gen == Gen.MEDIUM_SFX) AmbienceModel.MEDIUM_MUSIC else null,
                    onCompile = { k, n -> progress(place[batch.jobs.first()] ?: 0, ordered.size, "подготовка NPU, часть $k из $n (один раз)") })
                generator!!.attachNpu(npu)
                generator!!.npuShort = AmbienceSettings.npuShort(prefs)
                if (npu != null) {
                    // Конвейер: NPU считает DiT следующего клипа, пока CPU декодирует предыдущий.
                    val wall = SystemClock.elapsedRealtime(); val cpu = Process.getElapsedCpuTime()
                    val g = generator!!
                    fun produceNpu(job: Job) = job to g.latents(job.prompt, job.seconds, job.seed, Sa3Generator.Track(cancelled))
                    fun save(job: Job, result: Sa3Generator.Result) {
                        progress(place[job] ?: 0, ordered.size, job.label)
                        synchronized(doc) { saveClip(context, sha, dir, doc, job, result, npu = true) }
                    }
                    var helper: Sa3Generator? = null
                    try {
                        if (AmbienceSettings.parallel(prefs) && batch.gen == Gen.MUSIC && g.npuDecoder != null) {
                            // Музыка: NPU считает DiT; декодирует тот, кто свободен — NPU или процессор.
                            ParallelPipeline(batch.jobs, listOf(::produceNpu), listOf(
                                { r: Pair<Job, Sa3Generator.Latents> -> save(r.first, g.decodeWith(r.second, r.first.seconds, npu = true)) },
                                { r: Pair<Job, Sa3Generator.Latents> -> save(r.first, g.decodeWith(r.second, r.first.seconds, npu = false)) })).run()
                        } else if (AmbienceSettings.parallel(prefs)) {
                            // Фоны и события: второй DiT на процессоре (своя копия моделей) считает другие клипы, пока NPU
                            // занят; декодер на процессоре один на обоих.
                            val keep = AmbienceSettings.keepModels(prefs)
                            val h = when (batch.gen) {
                                Gen.MUSIC -> AmbienceModel.musicGenerator(context, threads, null, keep)
                                Gen.MEDIUM_SFX -> AmbienceModel.mediumSfxGenerator(context, threads, keep)
                                Gen.SMALL -> Sa3Generator(AmbienceModel.root(context), threads, null, keep = keep)
                            }.also { helper = it }
                            ParallelPipeline(batch.jobs, listOf(::produceNpu,
                                    { job: Job -> job to h.latents(job.prompt, job.seconds, job.seed, Sa3Generator.Track(cancelled)) }),
                                listOf { r: Pair<Job, Sa3Generator.Latents> -> save(r.first, g.decode(r.second, r.first.seconds)) }).run()
                        } else ClipPipeline(batch.jobs,
                            produce = { job -> produceNpu(job) },
                            consume = { (job, latents) -> save(job, g.decode(latents, job.seconds)) }).run()
                        npuFailures = 0
                        helper?.close(); helper = null
                        Log.i("Ambience", "batch ${batch.gen} npu=true parallel=${AmbienceSettings.parallel(prefs)} clips=${batch.jobs.size} " +
                            "wall=${SystemClock.elapsedRealtime() - wall} cpu=${Process.getElapsedCpuTime() - cpu}")
                    } catch (e: InterruptedException) {
                        helper?.close()
                        throw e
                    } catch (e: Throwable) {
                        helper?.close(); helper = null
                        Log.w("Ambience", "NPU failed mid-batch after ${batch.jobs.count { File(dir, it.file).isFile }} clips " +
                            "(${e.javaClass.simpleName}: ${e.message}); the rest on the CPU")
                        runCatching { npu!!.close() }
                        npu = null
                        npuRetryAt = SystemClock.elapsedRealtime() + com.brahmadeo.supertonic.tts.utils.Npu.retryDelayMs(++npuFailures)
                        generator!!.attachNpu(null)
                        for (job in batch.jobs) {
                            if (File(dir, job.file).isFile) continue
                            if (cancelled()) throw InterruptedException("cancelled")
                            progress(place[job] ?: 0, ordered.size, job.label)
                            saveClip(context, sha, dir, doc, job, generator!!.generate(job.prompt, job.seconds, job.seed, cancelled), npu = false)
                        }
                    }
                } else {
                    for (job in batch.jobs) {
                        if (cancelled()) throw InterruptedException("cancelled")
                        progress(place[job] ?: 0, ordered.size, job.label)
                        saveClip(context, sha, dir, doc, job, generator!!.generate(job.prompt, job.seconds, job.seed, cancelled), npu = false)
                    }
                }
            }
            progress(ordered.size, ordered.size, "")
        } finally { generator?.npuDecoder?.close(); generator?.close(); npu?.close() }
    }

    /** Чем делается клип: малой моделью, средней (звуки событий по выбору) или моделью музыки. */
    private enum class Gen { SMALL, MEDIUM_SFX, MUSIC }

    private fun genOf(context: Context, job: Job): Gen = when {
        job.music -> Gen.MUSIC
        job.event && AmbienceSettings.eventsMedium(context.getSharedPreferences("SupertonicPrefs", 0)) &&
            AmbienceModel.mediumReady(context) -> Gen.MEDIUM_SFX
        else -> Gen.SMALL
    }

    /** Клипы с общим генератором и общей NPU-сессией: подряд идущие, одной модели и одного места счёта DiT — на NPU
     * он идёт у клипов точной длины графа (30 с), а при «Короткие звуки на NPU» и у более коротких. */
    private class Batch(val gen: Gen, val npu: Boolean) { val jobs = ArrayList<Job>() }

    private fun batches(context: Context, ordered: List<Job>): List<Batch> {
        val short = AmbienceSettings.npuShort(context.getSharedPreferences("SupertonicPrefs", 0))
        val planned = HashMap<Gen, Boolean>()
        val out = ArrayList<Batch>()
        for (job in ordered) {
            val gen = genOf(context, job)
            val npu = planned.getOrPut(gen) {
                when (gen) {
                    Gen.SMALL -> AmbienceModel.npuPlanned(context, false)
                    Gen.MUSIC -> AmbienceModel.npuPlanned(context, true)
                    Gen.MEDIUM_SFX -> AmbienceModel.mediumNpuPlanned(context)
                }
            } && Sa3Generator.fitsGraph(Sa3Generator.latentsFor(job.seconds), AmbienceModel.NPU_LENGTH, short)
            val last = out.lastOrNull()
            if (last == null || last.gen != gen || last.npu != npu) out.add(Batch(gen, npu))
            out.last().jobs.add(job)
        }
        return out
    }

    /** Всё, что делается с готовым звуком: выравнивание громкости, AAC в папку книги, строка манифеста и копия в
     * базу звуков. В конвейере это второй поток, пока первый считает следующий клип. */
    private fun saveClip(context: Context, sha: String, dir: File, doc: JSONObject, job: Job,
                         result: Sa3Generator.Result, npu: Boolean) {
        val pcm = result.pcm
        if (job.music && MusicEnhance.enabled(context)) MusicEnhance.apply(pcm, Sa3Generator.SAMPLE_RATE)
        val event = job.id.startsWith("k_") || job.id.startsWith("e")
        // Звук события выравнивают по пику, а не по громкости: щелчок и удар двери по природе разной громкости,
        // но оба должны пробиваться сквозь фон. Обстановка и музыка — по громкости (BS.1770).
        val gain = if (event) Loudness.peak(pcm)
            else if (job.music) Loudness.normalize(pcm, Sa3Generator.SAMPLE_RATE, Loudness.MUSIC_LUFS, maxPeakDb = Loudness.MUSIC_PEAK_DB)
            else Loudness.normalize(pcm, Sa3Generator.SAMPLE_RATE, Loudness.TARGET_LUFS)
        // Сначала во временный файл: оборванная запись (сбой, остановка) не должна выглядеть готовым клипом —
        // по наличию файла решается, что делать заново.
        val part = File(dir, job.file + ".part")
        AacFile.write(part, pcm, bitrate = BITRATE)
        check(part.renameTo(File(dir, job.file))) { "Не удалось сохранить ${job.file}" }
        // The bank grows: a sound made for this book is reused by the next one for free.
        if (!job.music && job.id.startsWith("k_") && SoundBank.on(context))
            SoundBank.store(context, job.id.removePrefix("k_"), File(dir, job.file),
                custom = !com.brahmadeo.supertonic.tts.books.EventKinds.known(job.id.removePrefix("k_")))
        doc.put(job.id, job.recipe).put(LUFS_KEY, Loudness.TARGET_LUFS)
        manifest(context, sha).writeText(doc.toString())
        Log.i("Ambience", "Sound ${job.file} ready (gain ${"%.1f".format(gain)} dB, npu=$npu, music=${job.music}, " +
            result.stages.joinToString(" ") { "${it.name}=${it.wallMs}" } + ")")
    }
}
