package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import android.content.Intent
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

/** Silent benchmark: generates ambience into cache/ambience/<name>.wav (nothing is played, no settings change).
 * Extras: ambiencePrompts "name|prompt;;name|prompt", ambienceSeconds (30), ambienceThreads (4), ambienceRuns (2). */
object AmbienceProbe {
    private const val TAG = "AmbienceProbe"
    private val DEFAULT = listOf(
        "train" to "Inside a moving passenger train, steady rhythmic wheel clacking on rails, low rumble, muffled cabin ambience",
        "cafe" to "Busy cafe ambience, quiet indistinct chatter, cups and spoons clinking, espresso machine in background",
    )

    /** Production NPU DiT (AmbienceModel.npuDit, switch forced on for the probe only), then optionally one forward
     * checked against the same graph on the ORT CPU (that CPU session inflates the process memory afterwards). */
    private fun npuDit(context: Context, threads: Int, prompt: String, check: Boolean): Sa3Generator.NpuDit? {
        val prefs = context.getSharedPreferences("SupertonicPrefs", 0)
        val had = prefs.contains(AmbienceModel.NPU_KEY); val old = prefs.getBoolean(AmbienceModel.NPU_KEY, false)
        prefs.edit().putBoolean(AmbienceModel.NPU_KEY, true).commit()
        try {
            if (!AmbienceModel.npuReady(context)) runBlocking { AmbienceModel.download(context) { text, _ -> Log.i(TAG, "DOWNLOAD $text") } }
            memory("before-npu")
            val wall = android.os.SystemClock.elapsedRealtime(); val cpu = android.os.Process.getElapsedCpuTime()
            val dit = AmbienceModel.npuDit(context) ?: return null.also { Log.e(TAG, "NPU DiT unavailable") }
            Log.i(TAG, "NPU LOAD wallMs=${android.os.SystemClock.elapsedRealtime() - wall} cpuMs=${android.os.Process.getElapsedCpuTime() - cpu}")
            memory("after-npu-load")
            val (hidden, mask) = Sa3Generator(AmbienceModel.root(context), threads).use { it.encodeText(prompt) }
            memory("after-t5")
            val rng = java.util.Random(1)
            val x = FloatArray(Sa3Generator.LATENT_CH * AmbienceModel.NPU_LENGTH) { rng.nextGaussian().toFloat() }
            val npu = (0 until 3).map { val t0 = android.os.SystemClock.elapsedRealtime(); val c0 = android.os.Process.getElapsedCpuTime()
                dit.velocity(x, 0.7f, hidden, mask, 30f).also { _ -> Log.i(TAG, "NPU FORWARD wallMs=${android.os.SystemClock.elapsedRealtime() - t0} cpuMs=${android.os.Process.getElapsedCpuTime() - c0}") } }.last()
            memory("after-npu-forward")
            System.gc(); Runtime.getRuntime().gc(); memory("after-gc")
            if (check) {
                val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
                ai.onnxruntime.OrtSession.SessionOptions().use { o ->
                    o.setIntraOpNumThreads(threads)
                    env.createSession(File(AmbienceModel.root(context), "dit_L323_main.onnx").path, o).use { cpuSession ->
                        val ref = Sa3Generator.NpuDit(cpuSession, dit.pre, AmbienceModel.NPU_LENGTH).velocity(x, 0.7f, hidden, mask, 30f)
                        var signal = 0.0; var noise = 0.0
                        for (i in ref.indices) { signal += ref[i].toDouble() * ref[i]; val d = ref[i] - npu[i]; noise += d.toDouble() * d }
                        Log.i(TAG, "NPU CHECK snrDb=${"%.1f".format(10 * kotlin.math.log10(signal / noise))}")
                    }
                }
            }
            return dit
        } finally {
            prefs.edit().apply { if (had) putBoolean(AmbienceModel.NPU_KEY, old) else remove(AmbienceModel.NPU_KEY) }.commit()
        }
    }

    /** PSS split by kind (summary.* from Debug.MemoryInfo, MB): where a big NPU footprint sits. */
    private fun memory(tag: String) {
        val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi)
        val stats = mi.memoryStats.filterKeys { it.startsWith("summary.") }.mapValues { (it.value.toLongOrNull() ?: 0L) / 1024 }
        Log.i(TAG, "MEM $tag " + stats.entries.joinToString(" ") { "${it.key.removePrefix("summary.")}=${it.value}" })
    }

    fun run(context: Context, intent: Intent) {
        if (!AmbienceModel.ready(context)) {
            Log.i(TAG, "DOWNLOAD start ${AmbienceModel.totalBytes() / 1048576} MB")
            var last = -1
            runBlocking { AmbienceModel.download(context) { text, p -> val pct = (p * 100).toInt(); if (pct / 5 != last) { last = pct / 5; Log.i(TAG, "DOWNLOAD $text") } } }
        }
        val prompts = intent.getStringExtra("ambiencePrompts")?.split(";;")?.map { it.substringBefore('|') to it.substringAfter('|') } ?: DEFAULT
        val seconds = intent.getIntExtra("ambienceSeconds", 30).toFloat()
        val threads = intent.getIntExtra("ambienceThreads", 4)
        val runs = intent.getIntExtra("ambienceRuns", 2)
        // Модель для пробы: small (по умолчанию) или medium — для сравнения качества на одних и тех же промптах.
        val model = intent.getStringExtra("ambienceModel") ?: "small"
        if (model == "medium" && !AmbienceModel.musicReady(context)) {
            Log.i(TAG, "DOWNLOAD medium")
            runBlocking { AmbienceModel.downloadMusic(context) { t, p -> Log.i(TAG, "DOWNLOAD $t ${(p * 100).toInt()}%") } }
        }
        val out = File(context.cacheDir, "ambience").apply { mkdirs() }
        val results = JSONArray()
        val npuDit = if (intent.getBooleanExtra("ambienceNpu", false)) npuDit(context, threads, prompts.first().second,
            intent.getBooleanExtra("ambienceNpuCheck", true)) else null
        if (npuDit != null && seconds != 30f) Log.w(TAG, "NPU graph is 30 s only; this run stays on CPU")
        Log.i(TAG, "PSS before generation ${Debug.getPss() / 1024} MB")
        // Сравниваем именно модели: декодер у обеих один и тот же SAME-S, иначе разница будет от декодера.
        val generator = if (model == "medium") {
            val m = AmbienceModel.music(context)
            Sa3Generator(AmbienceModel.root(context), threads, npuDit, m.ditFile, m.rungs, "dec_w8a8.tflite", keep = true)
        } else Sa3Generator(AmbienceModel.root(context), threads, npuDit)
        for (run in 0 until runs) for ((name, prompt) in prompts) {
            val peakPss = java.util.concurrent.atomic.AtomicLong(0); val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
            val sampler = thread(name = "ambience-pss") { while (sampling.get()) { peakPss.accumulateAndGet(Debug.getPss()) { a, b -> maxOf(a, b) }; Thread.sleep(200) } }
            val started = android.os.SystemClock.elapsedRealtime(); val cpu = android.os.Process.getElapsedCpuTime()
            try {
                val result = generator.generate(prompt, seconds, 42L + run) { Log.i(TAG, "STAGE run=$run $name ${it.name} wallMs=${it.wallMs} cpuMs=${it.cpuMs}") }
                val wall = android.os.SystemClock.elapsedRealtime() - started; val cpuMs = android.os.Process.getElapsedCpuTime() - cpu
                sampling.set(false); sampler.join()
                val pcm = result.pcm
                var peak = 0f; var sum = 0.0; var nan = 0
                for (v in pcm) { if (v.isNaN()) nan++ else { peak = maxOf(peak, abs(v)); sum += v * v } }
                val file = File(out, "${model}_${name}_run$run.wav"); Sa3Generator.writeWav(file, pcm)
                val aac = File(out, "${model}_${name}_run$run.m4a"); val enc = android.os.SystemClock.elapsedRealtime()
                AacFile.write(aac, pcm)
                Log.i(TAG, "AAC $name run=$run bytes=${aac.length()} encodeMs=${android.os.SystemClock.elapsedRealtime() - enc}")
                val row = JSONObject().put("model", model).put("run", run).put("name", name).put("threads", threads).put("npu", npuDit != null).put("seconds", seconds)
                    .put("wallMs", wall).put("cpuMs", cpuMs).put("peakPssMb", peakPss.get() / 1024)
                    .put("peak", peak.toDouble()).put("rms", sqrt(sum / pcm.size)).put("nan", nan)
                    .put("stages", JSONArray(result.stages.map { JSONObject().put("name", it.name).put("wallMs", it.wallMs).put("cpuMs", it.cpuMs) }))
                results.put(row)
                Log.i(TAG, "RESULT $row")
            } catch (e: Throwable) {
                sampling.set(false); sampler.join()
                Log.e(TAG, "FAILED $name ${e.javaClass.simpleName}: ${e.message}", e)
                results.put(JSONObject().put("run", run).put("name", name).put("error", "${e.javaClass.simpleName}: ${e.message}"))
            }
        }
        generator.close()
        npuDit?.close()
        File(out, "probe.json").writeText(results.toString(1))
        Log.i(TAG, "PROBE DONE ${results.length()}")
    }
}

/** Silent check of the LLM's scene places: cache/scene-probe.txt (sections start with "### ", one paragraph a
 * line) through SceneMarking with the configured cloud (its key stays in the app) → cache/scene-probe-out.json. */
object ScenesProbe {
    fun run(context: Context) {
        val lines = File(context.cacheDir, "scene-probe.txt").readLines()
        var section = -1
        val paragraphs = mutableListOf<com.brahmadeo.supertonic.tts.books.prepare.EpubBook.Paragraph>()
        for (l in lines) {
            if (l.startsWith("### ")) section++ else if (l.isNotBlank()) paragraphs += com.brahmadeo.supertonic.tts.books.prepare.EpubBook.Paragraph(maxOf(section, 0), l.trim())
        }
        val source = com.brahmadeo.supertonic.tts.books.prepare.BookPreparation.source(context).withThinking(false)
        val started = android.os.SystemClock.elapsedRealtime()
        var requests = 0
        val raws = JSONArray()
        val result = com.brahmadeo.supertonic.tts.books.prepare.SceneMarking.mark(paragraphs, { prompt ->
            requests++
            try {
                com.brahmadeo.supertonic.tts.llm.LlmProviders.cloudRequest(source.config, prompt, com.brahmadeo.supertonic.tts.books.prepare.SceneMarking.INSTRUCTION,
                    com.brahmadeo.supertonic.tts.books.prepare.SceneMarking.schema(), source.gemini, tokens = 8000, deadlineMs = 180_000L, provider = source.provider)
                    .also { raws.put(it.take(4000)) }
            } catch (e: Exception) {
                // Provider messages may embed URLs or keys: only the class and an HTTP status go into the file.
                raws.put("ERROR ${e.javaClass.simpleName} ${Regex("API HTTP \\d{3}").find(e.message.orEmpty())?.value.orEmpty()}"); throw e
            }
        }, { done, all -> Log.i("ScenesProbe", "batch $done/$all") })
        val out = JSONObject().put("raw", raws).put("provider", source.label).put("ms", android.os.SystemClock.elapsedRealtime() - started).put("requests", requests)
            .put("marked", result.marked).put("failed", result.failed).put("events", result.events).put("kinds", result.kinds)
            .put("dropped", result.dropped).put("ambience", result.index.toJson().apply { remove("index") })
            .put("paragraphs", JSONArray(paragraphs.mapIndexed { i, p -> JSONObject().put("i", i + 1).put("section", p.section)
                .put("place", result.places.getOrNull(i) ?: JSONObject.NULL).put("text", p.text.take(90)) }))
        File(context.cacheDir, "scene-probe-out.json").writeText(out.toString(1))
        Log.i("ScenesProbe", "DONE locations=${result.index.locations.size} failed=${result.failed} requests=$requests ms=${out.getLong("ms")}")
    }
}

/** «Своя музыка» through the real path (translation, generation, loop, saving into the list): cache/music-probe.txt has
 * one description a line; extra musicSeconds (60). Results (prompt, seconds, file) → cache/music-probe-out.json. */
object MusicProbe {
    fun run(context: Context, seconds: Int) {
        val lines = File(context.cacheDir, "music-probe.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
        if (!com.brahmadeo.supertonic.tts.ambience.AmbienceModel.musicReady(context))
            runBlocking { com.brahmadeo.supertonic.tts.ambience.AmbienceModel.downloadMusic(context) { text, _ -> Log.i("MusicProbe", text) } }
        val out = JSONArray()
        for (d in lines) {
            val started = android.os.SystemClock.elapsedRealtime(); val cpu = android.os.Process.getElapsedCpuTime()
            val row = JSONObject().put("description", d)
            try {
                val t = com.brahmadeo.supertonic.tts.music.GeneratedMusic.create(context, d, seconds, { Log.i("MusicProbe", it) }, { false })
                row.put("prompt", t.prompt).put("file", t.file.path).put("seconds", t.seconds)
            } catch (e: Exception) { row.put("error", "${e.javaClass.simpleName}: ${e.message}") }
            row.put("wallMs", android.os.SystemClock.elapsedRealtime() - started).put("cpuMs", android.os.Process.getElapsedCpuTime() - cpu)
            out.put(row); Log.i("MusicProbe", "RESULT $row")
            File(context.cacheDir, "music-probe-out.json").writeText(out.toString(1))
        }
        Log.i("MusicProbe", "DONE ${out.length()}")
    }
}
