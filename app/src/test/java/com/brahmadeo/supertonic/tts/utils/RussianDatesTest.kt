package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RussianDatesTest {
    private val numbers = RussianNumberNormalizer()
    private fun book(text: String) = RussianBookNormalizer.normalize(text)
    private fun llm(text: String) = numbers.prepareForLlm(text)

    @Test fun dayMonthYearIsOrdinalWithoutLeadingOne() {
        val expected = "Двадцатого августа тысяча девятьсот девяносто первого года произошёл путч."
        assertEquals(expected, book("20 августа 1991 года произошёл путч."))
        assertEquals(expected, llm("20 августа 1991 года произошёл путч.").text)
        assertEquals("Двадцатого августа тысяча девятьсот девяносто первого", book("20 августа 1991"))
        assertEquals("Он ушёл. Третьего мая вернулся.", book("Он ушёл. 3 мая вернулся."))
        assertEquals("Пятого мая две тысячи двадцать четвёртого года", book("05.05.2024"))
    }

    @Test fun prepositionSelectsTheCase() {
        assertEquals("к первому сентября две тысячи первого года", book("к 1 сентября 2001 года"))
        assertEquals("с двадцатого августа по третье сентября", book("с 20 августа по 3 сентября"))
        assertEquals("в ночь на двадцатое августа", book("в ночь на 20 августа"))
        assertEquals("Сегодня двадцатое августа.", book("Сегодня 20 августа."))
        assertEquals("перед третьим мая", book("перед 3 мая"))
    }

    @Test fun yearsFollowTheirNoun() {
        assertEquals("В тысяча девятьсот пятом году", book("В 1905 году"))
        assertEquals("В двухтысячном году.", book("В 2000 году."))
        assertEquals("к двухтысячному году", book("к 2000 году"))
        assertEquals("до тысяча девятисотого года", book("до 1900 года"))
        assertEquals("Тысяча девятьсот сорок пятый год", book("1945 год"))
        assertEquals("за тысяча девятьсот сорок пятым годом", book("за 1945 годом"))
        assertEquals("в тысяча девятьсот девяносто первом году он уехал", book("в 1991 г. он уехал"))
        assertEquals("Это было в тысяча девятьсот девяносто первом году. Потом", book("Это было в 1991 г. Потом"))
        assertEquals("человек тысяча девятьсот девяносто первого года рождения", book("человек 1991 г. рождения"))
    }

    @Test fun ordinalsUseStressedEndingsAndThousandStems() {
        assertEquals("второй век", book("II век"))
        assertEquals("второй этаж", book("2-й этаж"))
        assertEquals("сороковой", RussianBookNormalizer.ordinal(40, "ый"))
        assertEquals("тысячного", RussianBookNormalizer.ordinal(1000, "ого"))
        assertEquals("тысяча сотый", RussianBookNormalizer.ordinal(1100, "ый"))
        assertEquals("две тысячи третьем", RussianBookNormalizer.ordinal(2003, "ом"))
        assertEquals("третьим", RussianBookNormalizer.ordinal(3, "ым"))
    }

    @Test fun llmSpansCoverOnlyNumeralWords() {
        val prepared = llm("Из 1 101 записи 20 августа 1991 года было 5 глав.")
        assertEquals("Из одной тысячи ста одной записи двадцатого августа тысяча девятьсот девяносто первого года было пять глав.", prepared.text)
        assertEquals(listOf("одной тысячи ста одной", "двадцатого", "тысяча девятьсот девяносто первого", "пять"),
            prepared.ranges.map { prepared.text.substring(it) })
    }

    @Test fun llmMayOnlyInflectTheSameDate() {
        fun accept(source: String, answer: String): String? {
            val prepared = llm(source)
            return com.brahmadeo.supertonic.tts.llm.PreparedTextValidator.validate(prepared.text, answer,
                numberRanges = prepared.ranges, requireStress = false)
        }
        assertNotNull(accept("Сегодня, 20 августа, праздник.", "Сегодня, двадцатое августа, праздник."))
        assertNotNull(accept("К 2000 году всё изменилось.", "К двухтысячному году всё изменилось."))
        assertNull(accept("20 августа 1991 года.", "двадцать первого августа тысяча девятьсот девяносто первого года."))
        assertNull(accept("20 августа 1991 года.", "двадцатого августа тысяча девятьсот девяносто второго года."))
    }

    @Test fun nonDatesStayUntouched() {
        assertEquals("31.02.2024", RussianBookNormalizer.normalize("31.02.2024", expandNumbers = false))
        assertEquals("12:30 и 1990-х годах", RussianDates.expand("12:30 и 1990-х годах").text)
        assertEquals("32 августа", RussianDates.expand("32 августа").text)
    }
}
