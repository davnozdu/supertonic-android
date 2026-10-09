package com.brahmadeo.supertonic.tts.llm

/** API compatibility rules, independent of the dynamically fetched model catalogue. */
object ThinkingPolicy {
    data class GeminiControl(val field: String, val value: Any, val minimumOnly: Boolean = false)

    fun gemini(model: String, enabled: Boolean): GeminiControl? {
        val name = model.lowercase().removePrefix("models/")
        if (name.startsWith("gemini-2.5-")) {
            val pro = name.contains("pro")
            return GeminiControl("thinkingBudget", if (enabled) 1024 else if (pro) 128 else 0, pro && !enabled)
        }
        val version = Regex("^gemini-(\\d+)(?:\\.(\\d+))?-").find(name) ?: return null
        if (version.groupValues[1].toInt() < 3) return null
        val major = version.groupValues[1].toInt()
        val minor = version.groupValues[2].toIntOrNull() ?: 0
        val minimum = if (name.contains("pro") || major > 3 || minor >= 7) "LOW" else "MINIMAL"
        // Gemini 3+ permits a minimum reasoning level, not a guaranteed full shutdown.
        return GeminiControl("thinkingLevel", if (enabled) "HIGH" else minimum, !enabled)
    }

    /** [effort]: a lower level after the thinking did not fit the answer limit (the nearest supported level not
     * above it); null — the default. */
    fun ollama(values: List<Any>, enabled: Boolean, model: String, effort: String? = null): Any {
        if (!enabled && false in values) return false
        val levels = values.filterIsInstance<String>()
        if (enabled && effort != null && levels.isNotEmpty()) {
            val order = listOf("max", "high", "medium", "low", "minimal")
            order.drop(maxOf(0, order.indexOf(effort))).firstOrNull { it in levels }?.let { return it }
        }
        if (enabled && true in values) return true
        if (levels.isNotEmpty()) {
            val order = if (enabled) listOf("medium", "high", "low", "minimal") else listOf("minimal", "low", "medium", "high")
            return order.firstOrNull { it in levels } ?: levels.first()
        }
        if (values == listOf(false)) return false
        if (values == listOf(true)) return true
        // Older servers do not expose /api/show thinking metadata.
        if (model.lowercase().substringBefore(':').startsWith("gpt-oss")) return if (enabled) "medium" else "low"
        return enabled
    }
}
