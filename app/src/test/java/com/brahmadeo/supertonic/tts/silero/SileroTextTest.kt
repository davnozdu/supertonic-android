package com.brahmadeo.supertonic.tts.silero

import org.junit.Assert.*
import org.junit.Test

class SileroTextTest {
    @Test fun explicitStressAndYo() {
        val text = SileroText.prepare("Светло́. Всё — хорошо́!")
        assertEquals("светл+о. вс+ё – хорош+о!", text)
        val ids = SileroText.sequence(text)
        assertEquals(2L, ids.first()); assertEquals(1L, ids.last())
        assertTrue(ids.all { it in 0..46 })
    }
    @Test fun veryLongSentencesStayBounded() {
        val text=("светл+о ".repeat(400)).trim()
        val parts=SileroText.bounded(text)
        assertTrue(parts.size>1)
        assertTrue(parts.all { it.length<=1000 && !it.endsWith('+') })
        assertEquals(text,parts.joinToString(" "))
        assertEquals(2500,SileroText.bounded("а".repeat(2500)).sumOf { it.length })
        val sentenceParts=com.brahmadeo.supertonic.tts.tera.TeraPunctuationPauses.split("А. С. Пушкин спросил: ты готов? Да, конечно!",0,420,sentenceOnly=true)
        assertEquals(2,sentenceParts.size)
        assertTrue(sentenceParts.first().text.startsWith("А. С. Пушкин"))
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
