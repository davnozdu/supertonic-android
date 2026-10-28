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
        val generator = Sa3Generator(AmbienceModel.root(context), threads)
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
                val row = JSONObject().put("run", run).put("name", name).put("threads", threads).put("seconds", seconds)
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
        File(out, "probe.json").writeText(results.toString(1))
        Log.i(TAG, "PROBE DONE ${results.length()}")
    }
}
