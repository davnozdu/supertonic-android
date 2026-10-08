package com.brahmadeo.supertonic.tts.utils
import org.junit.Assert.*
import org.junit.Test

class RussianBookNormalizerTest {
    @Test fun countedAndGroupedNumbers() {
        assertEquals("В списке одна тысяча одно имя и одна тысяча сто одна запись.",RussianBookNormalizer.normalize("В списке 1001 имя и 1101 запись."))
        assertEquals("одна тысяча сто один",RussianBookNormalizer.normalize("1 101"))
        assertEquals("без двадцати пяти записей",RussianBookNormalizer.normalize("без 25 записей"))
        assertEquals("к двумстам тридцати четырём",RussianBookNormalizer.normalize("к 234"))
    }
    @Test fun groupedNumbersBeforeUnitsAndHugeValues() {
        assertEquals("Я прошёл одна тысяча пятьсот километров.",RussianBookNormalizer.normalize("Я прошёл 1 500 км."))
        assertEquals("Нужно две тысячи килограммов.",RussianBookNormalizer.normalize("Нужно 2 000 кг."))
        assertEquals("Я прошёл одна тысяча пятьсот километров.",RussianBookNormalizer.normalize("Я прошёл 1 500 км.",expandNumbers=false,dates=false))
        assertEquals("тысяча пятисотый раз",RussianBookNormalizer.normalize("1 500-й раз"))
        // Beyond what the speller reads: no crash, the digits stay.
        assertTrue(RussianBookNormalizer.normalize("Цена 1234567890123 руб.").contains("1234567890123"))
    }
    @Test fun datesTimeYearsAndOrdinals() {
        assertEquals("Пятого мая две тысячи двадцать четвёртого года",RussianBookNormalizer.normalize("05.05.2024"))
        assertEquals("два часа одна минута",RussianBookNormalizer.normalize("02:01"))
        assertEquals("в две тысячи двадцать четвёртом году",RussianBookNormalizer.normalize("в 2024 году"))
        assertEquals("двадцать первая глава",RussianBookNormalizer.normalize("21-я глава"))
        assertEquals("четвёртый век",RussianBookNormalizer.normalize("IV век"))
        assertEquals("31.02.2024",RussianBookNormalizer.normalize("31.02.2024",expandNumbers=false))
    }
    @Test fun unitCases() {
        assertEquals("до одного километра",RussianBookNormalizer.normalize("до 1 км"))
        assertEquals("без двух рублей",RussianBookNormalizer.normalize("без 2 ₽"))
        assertEquals("с пятью килограммами",RussianBookNormalizer.normalize("с 5 кг"))
        assertEquals("к двадцати метрам",RussianBookNormalizer.normalize("к 20 м"))
        assertEquals("сто долларов",RussianBookNormalizer.normalize("$100"))
        assertEquals("Третьего мая",RussianBookNormalizer.normalize("3 мая"))
    }
    @Test fun unitsFractionsAndPhones() {
        assertEquals("два километра пять килограммов один процент",RussianBookNormalizer.normalize("2 км 5 кг 1%"))
        assertEquals("одна третья",RussianBookNormalizer.normalize("1/3"))
        assertEquals("три целых четырнадцать сотых",RussianBookNormalizer.normalize("3,14"))
        assertEquals("плюс семь девятьсот двенадцать триста сорок пять шестьдесят семь восемьдесят девять",RussianBookNormalizer.normalize("+7 (912) 345-67-89"))
    }
    @Test fun abbreviationsFootnotesAndWraps() {
        assertEquals("сим-карта, пин-код, гиф, миди, вайфай, вай-фай, хай-фай.",RussianBookNormalizer.normalize("SIM-карта, PIN-код, GIF, MIDI, WIFI, Wi-Fi, hi-fi."))
        assertEquals("то есть эф-эс-бэ",RussianBookNormalizer.normalize("т.е. ФСБ"))
        assertEquals("зелёный лес",RussianBookNormalizer.normalize("зелё-\nный лес [1]"))
    }
    @Test fun explicitStressAndValueArePreserved() {
        assertEquals("све+тло, берёза, одна тысяча сто одно имя.",RussianBookNormalizer.normalize("све+тло, берёза, 1101 имя."))
        val once=RussianBookNormalizer.normalize("05.05.2024 в 02:01, 3,14 кг, SIM-карта [1]")
        assertEquals(once,RussianBookNormalizer.normalize(once))
    }
}
