package com.brahmadeo.supertonic.tts.llm

internal data class PreparedSpeechText(val text: String, val llmProcessed: Boolean = false, val voicePlan: List<VoiceRoleText> = emptyList())

/** One-time delivery of text already prepared for a queued reader request. */
internal class PreparedSpeechHandoff(private val limit: Int = 32) {
    private data class Item(val source: String, val prepared: PreparedSpeechText)
    private val items=java.util.ArrayDeque<Item>()
    private var generation=0L
    init { require(limit>0) }
    @Synchronized fun token(): Long = generation
    @Synchronized fun clear() { generation++; items.clear() }
    @Synchronized fun put(token: Long, source: String, prepared: String, llmProcessed: Boolean = false, voicePlan: List<VoiceRoleText> = emptyList()): Boolean {
        if(token!=generation) return false
        while(items.size>=limit) items.removeFirst()
        items.addLast(Item(source,PreparedSpeechText(prepared,llmProcessed,voicePlan)))
        return true
    }
    fun take(source: String): String? = takePrepared(source)?.text
    @Synchronized fun takePrepared(source: String): PreparedSpeechText? {
        val iterator=items.iterator()
        while(iterator.hasNext()) {
            val item=iterator.next()
            if(item.source==source) { iterator.remove(); return item.prepared }
        }
        return null
    }
}
