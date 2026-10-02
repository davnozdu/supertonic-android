package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.*
import org.junit.Test

class LlmLookaheadTest {
    @Test fun longBookKeepsBeginningAndAdvances() {
        val book = List(1000) { "x".repeat(1000) }
        assertEquals(16, LlmLookahead.end(book, 0, 16000))
        assertEquals(17, LlmLookahead.end(book, 1, 16000))
        assertEquals(916, LlmLookahead.end(book, 900, 16000))
        assertEquals(1000, LlmLookahead.end(book, 999, 16000))
    }
    @Test fun capsEntryCountAndKeepsOversizeFirstFragment() {
        assertEquals(128, LlmLookahead.end(List(1000) { "x" }, 0, 48000))
        assertEquals(1, LlmLookahead.end(listOf("x".repeat(5000), "next"), 0, 4000))
        assertEquals(0, LlmLookahead.end(emptyList(), 0, 4000))
    }
}
