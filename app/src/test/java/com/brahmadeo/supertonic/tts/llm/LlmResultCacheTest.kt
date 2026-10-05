package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmResultCacheTest {
    @Test fun exactParagraphIsReusedRegardlessOfBatch() {
        val cache = LlmResultCache<String>()
        cache.put("Старый замок стоял.", "Ста́рый за́мок стоя́л.")
        assertEquals("Ста́рый за́мок стоя́л.", cache.get("Старый замок стоял."))
        assertNull(cache.get("Старый замок"))
        cache.clear()
        assertNull(cache.get("Старый замок стоял."))
    }

    @Test fun boundedByCharactersAndEntriesInLruOrder() {
        val cache = LlmResultCache<String>(maxChars = 10, maxEntries = 2) { key, value -> key.length + value.length }
        cache.put("ab", "AB"); cache.put("cd", "CD")
        cache.get("ab")
        cache.put("ef", "EF")
        assertNull(cache.get("cd"))
        assertEquals("AB", cache.get("ab"))
        cache.put("toolong", "TOOLONG")
        assertNull(cache.get("toolong"))
        assertEquals(2, cache.size())
    }
}
