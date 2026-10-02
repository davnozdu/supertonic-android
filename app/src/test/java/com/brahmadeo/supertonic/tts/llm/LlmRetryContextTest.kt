package com.brahmadeo.supertonic.tts.llm
import org.junit.Assert.*
import org.junit.Test

class LlmRetryContextTest {
    @Test fun partialRetryKeepsNeighboursWithoutWholeBook() {
        assertEquals(listOf(4, 5, 6), LlmRetryContext.indices(List(20) { "x".repeat(200) }, listOf(5), 1000))
        assertEquals(listOf(5), LlmRetryContext.indices(List(20) { "x".repeat(200) }, listOf(5), 200))
        assertEquals(listOf(0, 1, 18, 19), LlmRetryContext.indices(List(20) { "x".repeat(200) }, listOf(0, 19), 1000))
    }
}
