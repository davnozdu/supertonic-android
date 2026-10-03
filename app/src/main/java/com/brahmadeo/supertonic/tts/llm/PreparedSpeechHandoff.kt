package com.brahmadeo.supertonic.tts.llm

/** One-time delivery of text already prepared for a queued reader request. */
internal class PreparedSpeechHandoff(private val limit: Int = 32) {
    private data class Item(val source: String, val prepared: String)
    private val items=java.util.ArrayDeque<Item>()
    private var generation=0L
    init { require(limit>0) }
    @Synchronized fun token(): Long = generation
    @Synchronized fun clear() { generation++; items.clear() }
    @Synchronized fun put(token: Long, source: String, prepared: String): Boolean {
        if(token!=generation) return false
        while(items.size>=limit) items.removeFirst()
        items.addLast(Item(source,prepared))
        return true
    }
    @Synchronized fun take(source: String): String? {
        val iterator=items.iterator()
        while(iterator.hasNext()) {
            val item=iterator.next()
            if(item.source==source) { iterator.remove(); return item.prepared }
        }
        return null
    }
}
