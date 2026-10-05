package com.brahmadeo.supertonic.tts.llm

/** Final, validated preparation of one exact paragraph (text + voice roles), as it was played.
 * Readers resend the same paragraph after pause, rewind or a page refresh, usually in a
 * different batch; reusing the earlier result skips the LLM call and keeps the same stress,
 * roles and therefore the same PCM cache key. Cleared with every settings change. */
class LlmResultCache<V>(private val maxChars: Int = 2_000_000, private val maxEntries: Int = 4096,
                        private val weight: (String, V) -> Int = { key, _ -> key.length }) {
    private val values = LinkedHashMap<String, V>(64, .75f, true)
    private var chars = 0L
    init { require(maxChars > 0 && maxEntries > 0) }
    @Synchronized fun get(input: String): V? = values[input]
    @Synchronized fun put(input: String, value: V) {
        values.remove(input)?.let { chars -= weight(input, it) }
        val size = weight(input, value)
        if (size > maxChars) return
        values[input] = value; chars += size
        while (chars > maxChars || values.size > maxEntries) {
            val oldest = values.entries.iterator().next()
            chars -= weight(oldest.key, oldest.value)
            values.remove(oldest.key)
        }
    }
    @Synchronized fun clear() { values.clear(); chars = 0 }
    @Synchronized fun size() = values.size
}
