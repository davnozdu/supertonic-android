package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class NameStressTest {
    @Test fun learnsNamesInsideSentencesOnly() {
        NameStress.clear()
        NameStress.learn("Вошёл князь Мы́шкин. Ста́рый дом стоя́л у Рого́жина.")
        assertEquals(listOf("Мы́шкин", "Рого́жина"), NameStress.hint(listOf("Мышкин зашёл к Рогожина и к Дому.")))
        // "Ста́рый" starts a sentence: an ordinary word, not learned.
        assertEquals(emptyList<String>(), NameStress.hint(listOf("Старый")))
    }

    @Test fun fillsOnlyUnmarkedKnownNames() {
        NameStress.clear()
        NameStress.learn("и Наста́сья Фили́пповна вошла́")
        assertEquals("Наста́сья Фили́пповна вошла́, а князь Мышкин молча́л.",
            NameStress.fill("Настасья Филипповна вошла́, а князь Мышкин молча́л."))
        // An LLM mark is kept even when the book used another one.
        assertEquals("Настасья́", NameStress.fill("Настасья́"))
        assertEquals("настасья", NameStress.fill("настасья"))
    }

    @Test fun mostFrequentStressWins() {
        NameStress.clear()
        NameStress.learn("и Ивано́в, и Ивано́в, и Ива́нов")
        assertEquals("Ивано́в", NameStress.fill("Иванов"))
    }

    @Test fun dialogueDashAndQuotesStartSentences() {
        NameStress.clear()
        NameStress.learn("— Хорошо́, — сказа́л он. «Ла́дно» и Ганя")
        assertEquals(emptyList<String>(), NameStress.hint(listOf("Хорошо Ладно")))
    }
}
