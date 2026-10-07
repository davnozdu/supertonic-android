package com.brahmadeo.supertonic.tts.llm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Diagnostics only (SpeechDiagnosticsActivity --ez stressProbe true [--es provider OLLAMA|GEMINI]):
 * cache/stress-probe.txt (paragraphs separated by blank lines) goes through the same LLM path as reading,
 * in the same ~2400-char batches; cache/stress-probe-out.json gets source, prepared text and provider per
 * paragraph. Reads the saved LLM settings, changes none of them; keys never leave the app's own requests. */
internal object StressProbe {
    fun run(ctx: Context, provider: String?) {
        val input = File(ctx.cacheDir, "stress-probe.txt")
        val paragraphs = input.readText().split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        require(paragraphs.size in 1..200) { "stress-probe.txt: 1..200 paragraphs" }
        val saved = LlmSettings.load(ctx)
        val config = (provider?.let { saved.copy(mode = LlmMode.valueOf(it)) } ?: saved).copy(multiVoice = false)
        NameStress.clear()
        val out = JSONArray()
        val batch = mutableListOf<String>()
        fun flush() {
            if (batch.isEmpty()) return
            val started = System.currentTimeMillis()
            val results = LlmPreparation.testBatch(ctx, config, batch.toList())
            Log.i("SpeechCheck", "STRESS PROBE batch paragraphs=${batch.size} chars=${batch.sumOf { it.length }} ms=${System.currentTimeMillis() - started} providers=${results.map { it.provider }}")
            results.forEachIndexed { i, r ->
                out.put(JSONObject().put("source", batch[i]).put("text", r.text).put("provider", r.provider)
                    .put("fallback", r.fallback).put("ms", r.elapsedMs).put("reason", r.reason ?: ""))
            }
            batch.clear()
        }
        for (p in paragraphs) {
            if (batch.isNotEmpty() && batch.sumOf { it.length } + p.length > 2400) flush()
            batch += p
        }
        flush()
        File(ctx.cacheDir, "stress-probe-out.json").writeText(out.toString(1))
        Log.i("SpeechCheck", "STRESS PROBE DONE paragraphs=${paragraphs.size} mode=${config.mode} names=${NameStress.hint(paragraphs).size}")
    }
}
