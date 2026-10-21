package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class BracketReadingTest {
    private val words = BracketReading.Mode.WORDS
    private val pause = BracketReading.Mode.PAUSE

    @Test fun wordsAroundTheBracketedText() {
        assertEquals("Он пришёл, скобка открывается, как всегда поздно, скобка закрывается, и сел.",
            BracketReading.apply("Он пришёл (как всегда поздно) и сел.", words))
        assertEquals("Он пришёл, скобка открывается, поздно, скобка закрывается.",
            BracketReading.apply("Он пришёл (поздно).", words))
        assertEquals("Скобка", BracketReading.apply("Скобка", words))
        assertEquals("скобка открывается, Примечание, скобка закрывается. Дальше.",
            BracketReading.apply("(Примечание). Дальше.", words))
        assertEquals("Слово, скобка открывается, пояснение, скобка закрывается, дальше.",
            BracketReading.apply("Слово (пояснение), дальше.", words))
    }

    @Test fun pauseIsACommaPair() {
        assertEquals("Он пришёл, как всегда поздно, и сел.", BracketReading.apply("Он пришёл (как всегда поздно) и сел.", pause))
        assertEquals("Он пришёл, редакция, домой.", BracketReading.apply("Он пришёл [редакция] домой.", pause))
    }

    @Test fun footnotesAndUnpairedBracketsAreNotRead() {
        assertEquals("Слово и дальше.", BracketReading.apply("Слово (1) и дальше.", words))
        assertEquals("Слово.", BracketReading.apply("Слово (*).", words))
        assertEquals("1) пункт", BracketReading.apply("1) пункт", words))
        assertEquals("Как есть (текст)", BracketReading.apply("Как есть (текст)", BracketReading.Mode.OFF))
    }
}
