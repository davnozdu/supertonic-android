package com.brahmadeo.supertonic.tts.utils

/** PCM ownership is independent of the native model's inference lock. */
internal class SpeechAudioCache(private val maxEntries: Int = 256, private val maxEntryBytes: Int = 16*1024*1024) {
    data class Hit(val pcm: ByteArray, val entries: Int, val retainedBytes: Long)
    private val entries=LinkedHashMap<String,ByteArray>(32,.75f,true)
    private var retainedBytes=0L
    @Synchronized fun get(key: String): Hit? = entries[key]?.let { Hit(it,entries.size,retainedBytes) }
    @Synchronized fun clear() { entries.clear(); retainedBytes=0 }
    @Synchronized fun put(key: String, pcm: ByteArray, limitBytes: Long) {
        if(pcm.isEmpty() || pcm.size>maxEntryBytes || pcm.size>limitBytes) return
        entries.remove(key)?.let { retainedBytes-=it.size }
        entries[key]=pcm; retainedBytes+=pcm.size
        while(retainedBytes>limitBytes || entries.size>maxEntries) {
            val first=entries.entries.first()
            retainedBytes-=first.value.size; entries.remove(first.key)
        }
    }
}
