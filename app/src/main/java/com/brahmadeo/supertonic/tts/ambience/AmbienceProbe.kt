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
            val wall = android.os.SystemClock.elapsedRealtime(); val cpu = android.os.Process.getElapsedCpuTime()
            val dit = AmbienceModel.npuDit(context) ?: return null.also { Log.e(TAG, "NPU DiT unavailable") }
            Log.i(TAG, "NPU LOAD wallMs=${android.os.SystemClock.elapsedRealtime() - wall} cpuMs=${android.os.Process.getElapsedCpuTime() - cpu}")
            val (hidden, mask) = Sa3Generator(AmbienceModel.root(context), threads).encodeText(prompt)
            val rng = java.util.Random(1)
            val x = FloatArray(Sa3Generator.LATENT_CH * AmbienceModel.NPU_LENGTH) { rng.nextGaussian().toFloat() }
            val npu = (0 until 3).map { val t0 = android.os.SystemClock.elapsedRealtime(); val c0 = android.os.Process.getElapsedCpuTime()
                dit.velocity(x, 0.7f, hidden, mask, 30f).also { _ -> Log.i(TAG, "NPU FORWARD wallMs=${android.os.SystemClock.elapsedRealtime() - t0} cpuMs=${android.os.Process.getElapsedCpuTime() - c0}") } }.last()
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
        val out = File(context.cacheDir, "ambience").apply { mkdirs() }
        val results = JSONArray()
        val npuDit = if (intent.getBooleanExtra("ambienceNpu", false)) npuDit(context, threads, prompts.first().second,
            intent.getBooleanExtra("ambienceNpuCheck", true)) else null
        if (npuDit != null && seconds != 30f) Log.w(TAG, "NPU graph is 30 s only; this run stays on CPU")
        Log.i(TAG, "PSS before generation ${Debug.getPss() / 1024} MB")
        val generator = Sa3Generator(AmbienceModel.root(context), threads, npuDit)
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
                val file = File(out, "${name}_run$run.wav"); Sa3Generator.writeWav(file, pcm)
                val aac = File(out, "${name}_run$run.m4a"); val enc = android.os.SystemClock.elapsedRealtime()
                AacFile.write(aac, pcm)
                Log.i(TAG, "AAC $name run=$run bytes=${aac.length()} encodeMs=${android.os.SystemClock.elapsedRealtime() - enc}")
                val row = JSONObject().put("run", run).put("name", name).put("threads", threads).put("npu", npuDit != null).put("seconds", seconds)
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
        npuDit?.close()
        File(out, "probe.json").writeText(results.toString(1))
        Log.i(TAG, "PROBE DONE ${results.length()}")
    }
}
