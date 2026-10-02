package com.brahmadeo.supertonic.tts.llm

/** Retry rejected fragments with adjacent context, within the provider's limit. */
object LlmRetryContext {
    fun indices(texts: List<String>, missing: List<Int>, limit: Int): List<Int> {
        require(limit > 0 && missing.all { it in texts.indices })
        val selected = missing.toMutableSet()
        var chars = selected.sumOf { texts[it].length }
        for (i in missing) for (j in listOf(i - 1, i + 1)) {
            if (j in texts.indices && j !in selected && chars + texts[j].length <= limit) {
                selected += j; chars += texts[j].length
            }
        }
        return selected.sorted()
    }
}
