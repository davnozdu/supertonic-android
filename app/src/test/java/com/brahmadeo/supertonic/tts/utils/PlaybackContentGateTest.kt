package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.*
import org.junit.Test

class PlaybackContentGateTest {
    private fun id(text: String) = PlaybackContentGate.fingerprint(text)
    @Test fun connectingDoesNotReplaceArticleWithSavedBook() {
        val gate = PlaybackContentGate().apply { expect("Новая статья") }
        assertFalse(gate.accept(id("Старая книга"), "Старая книга", -1))
        assertFalse(gate.accept(id("Старая книга"), "Старая книга", 20))
        assertTrue(gate.awaitingReplacement())
        assertTrue(gate.accept(id("Новая статья"), "Новая статья", 12))
        assertFalse(gate.awaitingReplacement())
    }
    @Test fun staleCallbackCannotAdoptAnotherStoredText() {
        val gate = PlaybackContentGate().apply { expect("Новая статья") }
        assertFalse(gate.accept(id("Старая книга"), "Новая статья", 20))
        assertFalse(gate.accept(id("Новая статья"), "Старая книга", 12))
        assertTrue(gate.accept(id("Новая статья"), "Новая статья", 12))
        assertFalse(gate.accept(id("Старая книга"), "Новая статья", 20))
    }
    @Test fun acknowledgedReadingCanAdvanceToContinuation() {
        val gate = PlaybackContentGate().apply { expect("Часть 1") }
        assertTrue(gate.accept(id("Часть 1"), "Часть 1", 10))
        assertTrue(gate.accept(id("Часть 2"), "Часть 2", 15))
    }
    @Test fun sameTextStillWaitsForActualNewPlaybackAndResumeCanAttach() {
        val gate = PlaybackContentGate().apply { expect("Статья") }
        assertFalse(gate.accept(id("Статья"), "Статья", -1))
        gate.resume()
        assertTrue(gate.accept(id("Статья"), "Статья", -1))
        assertFalse(gate.accept("", "Статья", 10))
    }
}
