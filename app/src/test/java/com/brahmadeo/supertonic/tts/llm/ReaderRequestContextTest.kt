package com.brahmadeo.supertonic.tts.llm
import org.junit.Assert.*
import org.junit.Test
class ReaderRequestContextTest {
    @Test fun queuedFutureDoesNotChangeContextOfAnEarlierRequest() {
        val c = ReaderRequestContext()
        c.record(1,"Анна вошла в комнату.")
        c.record(1,"— Ты здесь?")
        c.record(1,"Совсем другая будущая строка.")
        assertEquals(listOf("вошла","в","комнату"),c.take(1,"— Ты здесь?"))
    }
    @Test fun repeatedWordsHaveSeparateOccurrenceContexts() {
        val c = ReaderRequestContext()
        c.record(1,"Он спросил Анну."); c.record(1,"Да.")
        c.record(1,"Она спросила Ивана."); c.record(1,"Да.")
        assertEquals(listOf("он","спросил","анну"),c.take(1,"Да."))
        assertEquals(listOf("она","спросила","ивана"),c.take(1,"Да."))
    }
    @Test fun clientsAndExplicitStopsAreIsolated() {
        val c = ReaderRequestContext()
        c.record(1,"Слова первой книги."); c.record(2,"Слова другой книги.")
        assertEquals(emptyList<String>(),c.take(2,"Слова другой книги."))
        c.clear(1)
        assertEquals(emptyList<String>(),c.take(1,"Новый текст."))
    }
}
