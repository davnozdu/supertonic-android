package com.brahmadeo.supertonic.tts.llm

/** Keep the next pages queued, rather than evicting their beginning with a whole book. */
object LlmLookahead {
    fun end(texts: List<String>, start: Int, maxChars: Int, maxEntries: Int = 128): Int {
        require(maxChars > 0 && maxEntries > 0)
        var end = start.coerceIn(0, texts.size)
        val first = end
        var chars = 0L
        while (end < texts.size && end - first < maxEntries) {
            val next = texts[end].length
            if (end > first && chars + next > maxChars) break
            chars += next
            end++
        }
        return end
    }
}
