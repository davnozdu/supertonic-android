package com.brahmadeo.supertonic.tts.llm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Diagnostics only (SpeechDiagnosticsActivity --ez stressProbe true [--es provider OLLAMA|GEMINI]
 * [--es sources silero,ruaccent,names,arbiter,yo,llm,llmyo] — the stress/ё sources for this run only):
 * cache/stress-probe.txt (paragraphs separated by blank lines) goes through the same LLM path as reading,
 * in the same ~2400-char batches; cache/stress-probe-out.json gets source, prepared text and provider per
 * paragraph. Reads the saved LLM settings, changes none of them; keys never leave the app's own requests. */
internal object StressProbe {
    fun run(ctx: Context, provider: String?, verifyThinking: Boolean? = null, verifier: String? = null,
            engine: String? = null, localThinking: Boolean? = null, sources: String? = null) {
        val prefs = ctx.getSharedPreferences("SupertonicPrefs", 0)
        val saved = com.brahmadeo.supertonic.tts.utils.StressOptions.keys.associateWith { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }
        val chosen = sources?.split(',')?.map { it.trim() }?.toSet()
        if (chosen != null) {
            val o = com.brahmadeo.supertonic.tts.utils.StressOptions
            prefs.edit().putBoolean(o.SILERO, "silero" in chosen).putBoolean(o.RUACCENT, "ruaccent" in chosen)
                .putBoolean(o.NAMES, "names" in chosen).putBoolean(o.ARBITER, "arbiter" in chosen)
                .putBoolean(o.YO_SILERO, "yo" in chosen).commit()
            sourcesOverride = chosen
        }
        LlmProviders.verifyThinkingOverride = verifyThinking
        LlmProviders.verifierOverride = verifier
        LocalModelDownload.engineOverride = engine
        thinkingOverride = localThinking
        LlmPreparation.probeRaw = java.util.concurrent.ConcurrentHashMap()
        if (engine != null) LlmProviders.unload() // the other engine may still hold its model
        try { runProbe(ctx, provider) } finally {
            LlmProviders.verifyThinkingOverride = null; LlmProviders.verifierOverride = null
            LlmPreparation.probeRaw = null; thinkingOverride = null
            if (chosen != null) {
                sourcesOverride = null
                prefs.edit().apply { saved.forEach { (k, v) -> if (v == null) remove(k) else putBoolean(k, v) } }.commit()
            }
            if (engine != null) { LlmProviders.unload(); LocalModelDownload.engineOverride = null }
        }
    }
    private var thinkingOverride: Boolean? = null
    private var sourcesOverride: Set<String>? = null

    private fun runProbe(ctx: Context, provider: String?) {
        val input = File(ctx.cacheDir, "stress-probe.txt")
        val paragraphs = input.readText().split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        require(paragraphs.size in 1..200) { "stress-probe.txt: 1..200 paragraphs" }
        if (provider == "OFFLINE") return offline(ctx, paragraphs)
        val override = File(ctx.cacheDir, "gemma-instruction.txt").takeIf { it.isFile }?.readText()
        LlmProviders.instructionOverride = override
        val cloudOverride = File(ctx.cacheDir, "cloud-instruction.txt").takeIf { it.isFile }?.readText()
        LlmProviders.cloudInstructionOverride = cloudOverride
        if (cloudOverride != null) Log.i("SpeechCheck", "STRESS PROBE cloud instruction override chars=${cloudOverride.length}")
        if (override != null) Log.i("SpeechCheck", "STRESS PROBE local instruction override chars=${override.length}")
        try { prepare(ctx, provider, paragraphs) } finally { LlmProviders.instructionOverride = null; LlmProviders.cloudInstructionOverride = null }
    }

    /** The offline chain alone (RUAccent + Silero vote), as the dictionary fallback marks a paragraph. */
    private fun offline(ctx: Context, paragraphs: List<String>) {
        val out = JSONArray()
        StressCheck.takeDecisions()
        val decisions = JSONArray()
        for (p in paragraphs) {
            val marked = com.brahmadeo.supertonic.tts.local.RussianStressVote.offline(ctx,
                com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.apply(p, "ru"))
                .replace(Regex("\\+([аеёиоуыэюяАЕЁИОУЫЭЮЯ])")) { it.groupValues[1] + "\u0301" }
            out.put(JSONObject().put("source", p).put("text", marked).put("provider", "offline").put("fallback", false).put("ms", 0).put("reason", ""))
        }
        StressCheck.takeDecisions().forEach { decisions.put(it) }
        File(ctx.cacheDir, "stress-probe-out.json").writeText(out.toString(1))
        File(ctx.cacheDir, "stress-probe-decisions.json").writeText(decisions.toString(1))
        Log.i("SpeechCheck", "STRESS PROBE DONE paragraphs=${paragraphs.size} mode=OFFLINE dictionary=${com.brahmadeo.supertonic.tts.local.RussianStressVote.dictionaryReady(ctx)}")
    }

    private fun prepare(ctx: Context, provider: String?, paragraphs: List<String>) {
        val saved = LlmSettings.load(ctx)
        val config = (provider?.let { saved.copy(mode = LlmMode.valueOf(it)) } ?: saved).copy(multiVoice = false)
            .let { c -> thinkingOverride?.let { c.copy(localThinking = it) } ?: c }
            .let { c -> sourcesOverride?.let { c.copy(stress = "llm" in it, restoreYo = "llmyo" in it) } ?: c }
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
                    .put("raw", LlmPreparation.probeRaw?.get(batch[i]) ?: "")
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
        Log.i("SpeechCheck", "STRESS PROBE DONE paragraphs=${paragraphs.size} mode=${config.mode} sources=${sourcesOverride ?: "saved"} names=${NameStress.hint(paragraphs).size}")
    }
}
