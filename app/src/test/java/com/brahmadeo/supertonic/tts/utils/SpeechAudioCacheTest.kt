package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.*
import org.junit.Test

class SpeechAudioCacheTest {
    @Test fun replacementDoesNotDoubleCountMemory() {
        val cache=SpeechAudioCache()
        cache.put("a",ByteArray(4),20)
        cache.put("a",ByteArray(7),20)
        assertEquals(7L,cache.get("a")!!.retainedBytes)
        assertEquals(1,cache.get("a")!!.entries)
    }
    @Test fun evictionUsesByteBudgetAndRecentAccess() {
        val cache=SpeechAudioCache()
        cache.put("a",ByteArray(4),8);cache.put("b",ByteArray(4),8)
        cache.get("a")
        cache.put("c",ByteArray(4),8)
        assertNull(cache.get("b"));assertNotNull(cache.get("a"));assertNotNull(cache.get("c"))
        assertEquals(8L,cache.get("c")!!.retainedBytes)
    }
    @Test fun oversizedAndEmptyAudioCannotDisplaceUsefulEntries() {
        val cache=SpeechAudioCache(maxEntryBytes=5)
        cache.put("a",ByteArray(4),20)
        cache.put("a",ByteArray(6),20);cache.put("b",ByteArray(0),20)
        assertEquals(4,cache.get("a")!!.pcm.size);assertNull(cache.get("b"))
        cache.put("c",ByteArray(5),4);assertNull(cache.get("c"))
    }
    @Test fun entryCountAndClearAreBounded() {
        val cache=SpeechAudioCache(maxEntries=2)
        for(i in 1..3) cache.put("$i",ByteArray(1),100)
        assertNull(cache.get("1"));assertEquals(2,cache.get("2")!!.entries)
        cache.clear();assertNull(cache.get("2"))
        cache.put("new",ByteArray(1),100);assertEquals(1L,cache.get("new")!!.retainedBytes)
    }
}
