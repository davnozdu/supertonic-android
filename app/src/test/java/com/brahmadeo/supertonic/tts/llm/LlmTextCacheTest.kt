package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.*
import org.junit.Test

class LlmTextCacheTest {
    @Test fun exactContextAndSettingsAreRequired() {
        val cache = LlmTextCache<String>()
        cache.put("gemini", listOf("старый замок", "дверной замок"), listOf("ста́рый за́мок", "дверно́й замо́к"))
        assertEquals(listOf("ста́рый за́мок", "дверно́й замо́к"), cache.get("gemini", listOf("старый замок", "дверной замок")))
        assertNull(cache.get("local", listOf("старый замок", "дверной замок")))
        assertNull(cache.get("gemini", listOf("старый замок")))
        cache.clear()
        assertNull(cache.get("gemini", listOf("старый замок", "дверной замок")))
    }
    @Test fun evictsLeastRecentlyUsedAndBoundsCharacters() {
        val cache = LlmTextCache<String>(maxChars = 12, maxEntries = 2)
        cache.put("s", listOf("one"), listOf("ONE"))
        cache.put("s", listOf("two"), listOf("TWO"))
        cache.get("s", listOf("one"))
        cache.put("s", listOf("tri"), listOf("TRI"))
        assertNull(cache.get("s", listOf("two")))
        assertNotNull(cache.get("s", listOf("one")))
        cache.put("s", listOf("oversize"), listOf("OVERSIZE"))
        assertNull(cache.get("s", listOf("oversize")))
        assertNotNull(cache.get("s", listOf("tri")))
    }
}
