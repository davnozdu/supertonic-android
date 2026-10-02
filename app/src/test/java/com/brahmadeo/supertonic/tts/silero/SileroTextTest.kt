package com.brahmadeo.supertonic.tts.silero

import org.junit.Assert.*
import org.junit.Test

class SileroTextTest {
    @Test fun explicitStressAndYo() {
        val text = SileroText.prepare("Светло́. Всё — хорошо́!")
        assertEquals("светл+о. всё – хорош+о!", text)
        val ids = SileroText.sequence(text)
        assertEquals(2L, ids.first()); assertEquals(1L, ids.last())
        assertTrue(ids.all { it in 0..46 })
    }
    @Test fun questionsAndExclamations() {
        assertEquals(2L, SileroText.type("ты гот+ов?"))
        assertEquals(1L, SileroText.type("где ты?"))
        assertEquals(3L, SileroText.type("чай или кофе?"))
        assertEquals(4L, SileroText.type("ты готов, правда?"))
        assertEquals(5L, SileroText.type("ура!"))
    }
    @Test fun mixedSentencesKeepTheirOwnType() {
        val text = "привет. ты готов? ура!"
        val ids = SileroText.typeIds(text, true)
        assertEquals(text.length + 2, ids.size)
        assertEquals(0L, ids[1])
        assertEquals(2L, ids[text.indexOf("ты") + 1])
        assertEquals(5L, ids[text.indexOf("ура") + 1])
        assertTrue(SileroText.typeIds(text, false).all { it == 0L })
    }
}
