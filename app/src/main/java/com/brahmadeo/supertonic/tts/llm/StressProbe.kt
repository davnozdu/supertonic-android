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
    fun run(ctx: Context, provider: String?, verifyThinking: Boolean? = null) {
        LlmProviders.verifyThinkingOverride = verifyThinking
        try { runProbe(ctx, provider) } finally { LlmProviders.verifyThinkingOverride = null }
    }

    private fun runProbe(ctx: Context, provider: String?) {
        val input = File(ctx.cacheDir, "stress-probe.txt")
        val paragraphs = input.readText().split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        require(paragraphs.size in 1..200) { "stress-probe.txt: 1..200 paragraphs" }
        if (provider == "OFFLINE") return offline(ctx, paragraphs)
        val override = File(ctx.cacheDir, "gemma-instruction.txt").takeIf { it.isFile }?.readText()
        LlmProviders.instructionOverride = override
        if (override != null) Log.i("SpeechCheck", "STRESS PROBE local instruction override chars=${override.length}")
        try { prepare(ctx, provider, paragraphs) } finally { LlmProviders.instructionOverride = null }
    }

    /** The offline chain alone (Silero Stress + dictionary), as the dictionary fallback marks a paragraph. */
    private fun offline(ctx: Context, paragraphs: List<String>) {
        val out = JSONArray()
        for (p in paragraphs) {
            val marked = com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.apply(
                com.brahmadeo.supertonic.tts.local.LocalRussianStress.apply(ctx, p), "ru")
                .replace(Regex("\\+([аеёиоуыэюяАЕЁИОУЫЭЮЯ])")) { it.groupValues[1] + "\u0301" }
            out.put(JSONObject().put("source", p).put("text", marked).put("provider", "offline").put("fallback", false).put("ms", 0).put("reason", ""))
        }
        File(ctx.cacheDir, "stress-probe-out.json").writeText(out.toString(1))
        Log.i("SpeechCheck", "STRESS PROBE DONE paragraphs=${paragraphs.size} mode=OFFLINE")
    }

    private fun prepare(ctx: Context, provider: String?, paragraphs: List<String>) {
        val saved = LlmSettings.load(ctx)
        val config = (provider?.let { saved.copy(mode = LlmMode.valueOf(it)) } ?: saved).copy(multiVoice = false)
        NameStress.clear()
        val out = JSONArray()
        val decisions = JSONArray()
        StressCheck.takeDecisions()
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
            StressCheck.takeDecisions().forEach { decisions.put(it) }
            batch.clear()
        }
        for (p in paragraphs) {
            if (batch.isNotEmpty() && batch.sumOf { it.length } + p.length > 2400) flush()
            batch += p
        }
        flush()
        File(ctx.cacheDir, "stress-probe-out.json").writeText(out.toString(1))
        File(ctx.cacheDir, "stress-probe-decisions.json").writeText(decisions.toString(1))
        Log.i("SpeechCheck", "STRESS PROBE DONE paragraphs=${paragraphs.size} mode=${config.mode} names=${NameStress.hint(paragraphs).size}")
    }
}
