package com.brahmadeo.supertonic.tts.utils

/** PCM ownership is independent of inference. Byte budget drives eviction;
 * a high metadata safeguard must not cap a 256 MB short-phrase cache at ~20 MB. */
internal class SpeechAudioCache(private val maxEntries: Int = 16_384, private val maxEntryBytes: Int = 16*1024*1024) {
    data class Hit(val pcm: ByteArray, val entries: Int, val retainedBytes: Long)
    data class Status(val entries: Int, val retainedBytes: Long, val aheadBytes: Long)
    private val entries=LinkedHashMap<String,ByteArray>(32,.75f,true)
    private val ahead = HashSet<String>()
    private val owners = HashMap<String, String>()
    private var retainedBytes=0L
    @Synchronized fun get(key: String, consumeAhead: Boolean = true, aheadOwner: String? = null): Hit? = entries[key]?.let {
        if (consumeAhead) { ahead.remove(key); owners.remove(key) }
        else { ahead.add(key); if (aheadOwner != null) owners[key] = aheadOwner }
        Hit(it,entries.size,retainedBytes)
    }
    @Synchronized fun status() = Status(entries.size, retainedBytes, entries.entries.sumOf { if (it.key in ahead) it.value.size.toLong() else 0L })
    @Synchronized fun releaseAhead(owner: String) {
        val keys = owners.filterValues { it == owner }.keys.toList()
        keys.forEach { ahead.remove(it); owners.remove(it) }
    }
    @Synchronized fun clear() { entries.clear(); ahead.clear(); owners.clear(); retainedBytes=0 }
    @Synchronized fun put(key: String, pcm: ByteArray, limitBytes: Long, preparedAhead: Boolean = false, aheadOwner: String? = null) {
        if(pcm.isEmpty() || pcm.size>maxEntryBytes || pcm.size>limitBytes) return
        entries.remove(key)?.let { retainedBytes-=it.size }
        entries[key]=pcm; retainedBytes+=pcm.size
        if (preparedAhead) { ahead.add(key); if (aheadOwner != null) owners[key] = aheadOwner }
        else { ahead.remove(key); owners.remove(key) }
        while(retainedBytes>limitBytes || entries.size>maxEntries) {
            // Keep imminent, unplayed audio. Played LRU entries make room first.
            // If all entries are future audio, reject newest instead of evicting the next phrase.
            val victim=entries.entries.firstOrNull { it.key !in ahead && it.key != key }
                ?: entries.entries.firstOrNull { it.key == key } ?: entries.entries.last()
            retainedBytes-=victim.value.size; ahead.remove(victim.key); owners.remove(victim.key); entries.remove(victim.key)
        }
    }
}
