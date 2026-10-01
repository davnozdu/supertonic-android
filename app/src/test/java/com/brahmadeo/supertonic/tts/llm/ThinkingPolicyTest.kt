package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.*
import org.junit.Test

class ThinkingPolicyTest {
    @Test fun ollamaUsesReportedCapabilities() {
        assertEquals(false, ThinkingPolicy.ollama(listOf(false, true), false, "deepseek-v4.1-flash"))
        assertEquals(true, ThinkingPolicy.ollama(listOf(false, true), true, "deepseek-v4.1-flash"))
        assertEquals("low", ThinkingPolicy.ollama(listOf("low", "medium", "high"), false, "any-model"))
        assertEquals("medium", ThinkingPolicy.ollama(listOf("low", "medium", "high"), true, "any-model"))
        assertEquals(false, ThinkingPolicy.ollama(listOf(false), true, "no-thinking-model"))
        assertEquals(true, ThinkingPolicy.ollama(listOf(true), false, "mandatory-thinking-model"))
        assertEquals("low", ThinkingPolicy.ollama(emptyList(), false, "gpt-oss:20b"))
    }

    @Test fun geminiUsesCompatibleControls() {
        assertEquals(0, ThinkingPolicy.gemini("gemini-2.5-flash", false)?.value)
        assertEquals(128, ThinkingPolicy.gemini("gemini-2.5-pro", false)?.value)
        assertTrue(ThinkingPolicy.gemini("gemini-2.5-pro", false)!!.minimumOnly)
        assertEquals("MINIMAL", ThinkingPolicy.gemini("gemini-3.1-flash-lite", false)?.value)
        assertEquals("LOW", ThinkingPolicy.gemini("gemini-3.8-flash", false)?.value)
        assertEquals("LOW", ThinkingPolicy.gemini("gemini-3-pro-preview", false)?.value)
        assertEquals("HIGH", ThinkingPolicy.gemini("gemini-3.1-flash-lite", true)?.value)
        assertNull(ThinkingPolicy.gemini("gemma-4-e2b-it", false))
    }
}
