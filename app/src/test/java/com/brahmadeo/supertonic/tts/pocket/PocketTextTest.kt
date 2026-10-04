package com.brahmadeo.supertonic.tts.pocket
import org.junit.Assert.*
import org.junit.Test
class PocketTextTest {
    @Test fun commonStressReachesModelAsAcuteWithoutLosingYoOrPunctuation() {
        assertEquals("Све́тло, ещё! Ты го́тов?",PocketText.prepare("Св+етло, ещё! Ты г+отов?"))
    }
    @Test fun bookParagraphsAreBoundedAndWordsStayIntact() {
        val text=("На улице шёл дождь, а в доме горел свет. ").repeat(20).trim()
        val chunks=PocketText.chunks(text)
        assertTrue(chunks.size>1)
        assertTrue(chunks.all { it.length<=180 })
        assertEquals(text,chunks.joinToString(" "))
    }
    @Test fun chunkBoundaryDoesNotDetachStressMark() {
        val chunks=PocketText.chunks("а".repeat(31)+"+а"+"б".repeat(50),32)
        assertTrue(chunks.none { it.startsWith('\u0301') })
        assertEquals("а".repeat(32)+"\u0301"+"б".repeat(50),chunks.joinToString(""))
    }
}
