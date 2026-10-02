package com.brahmadeo.supertonic.tts.foreign

import com.brahmadeo.supertonic.tts.utils.RussianNumberNormalizer
import org.junit.Assert.*
import org.junit.Test

class ForeignTextTest {
    @Test fun preservesMixedTextAndNumbers() {
        val text = "Он сказал: Hello 2026! Потом: Dobrý den, jak se máte? Всё хорошо."
        val parts = ForeignText.split(text)
        assertEquals(text, parts.joinToString("") { it.text })
        assertEquals(listOf(false, true, false, true, false), parts.map { it.foreign })
        assertTrue(parts[1].text.contains("2026"))
    }
    @Test fun foreignNumbersStayInTheirLanguage() {
        val text = "В списке 1001 имя. Hello 2026! Потом 1101 запись."
        val prepared = ForeignText.prepareNumbers(text, RussianNumberNormalizer())
        assertTrue(prepared.text.contains("одна тысяча одно имя"))
        assertTrue(prepared.text.contains("Hello 2026!"))
        assertTrue(prepared.text.contains("одна тысяча сто одна запись"))
        assertEquals(2, prepared.ranges.size)
        assertTrue(prepared.ranges.all { "Hello" !in prepared.text.substring(it) })
    }
    @Test fun czechAndEnglishRouting() {
        assertEquals("cs", ForeignText.language("Dobrý den, jak se máte?", "ru", "auto"))
        assertEquals("en", ForeignText.language("Hello world!", "ru", "auto"))
        assertEquals("cs", ForeignText.language("Dobry den", "ru", "auto"))
        assertEquals("cs", ForeignText.language("Hello", "ru", "cs"))
        assertEquals("en", ForeignText.language("Hello", "eng", "auto"))
    }
}
