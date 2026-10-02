package com.brahmadeo.supertonic.tts.llm

/** Exact-context LRU: the same word in another sentence must not inherit its stress. */
class LlmTextCache<K>(private val maxChars: Int = 8_000_000, private val maxEntries: Int = 256) {
    private data class Key<K>(val settings: K, val input: List<String>)
    private val values = LinkedHashMap<Key<K>, List<String>>(16, 0.75f, true)
    private var chars = 0L
    init { require(maxChars > 0 && maxEntries > 0) }
    @Synchronized fun get(settings: K, input: List<String>): List<String>? = values[Key(settings, input)]?.toList()
    @Synchronized fun put(settings: K, input: List<String>, output: List<String>) {
        require(input.size == output.size)
        val key = Key(settings, input.toList())
        val copy = output.toList()
        val size = input.sumOf { it.length.toLong() } + output.sumOf { it.length.toLong() }
        values.remove(key)?.let { chars -= weight(key, it) }
        if (size > maxChars) return
        values[key] = copy
        chars += size
        while (chars > maxChars || values.size > maxEntries) {
            val oldest = values.entries.iterator()
            val entry = oldest.next()
            chars -= weight(entry.key, entry.value)
            oldest.remove()
        }
    }
    private fun weight(key: Key<K>, value: List<String>) = key.input.sumOf { it.length.toLong() } + value.sumOf { it.length.toLong() }
    @Synchronized fun clear() { values.clear(); chars = 0 }
}
