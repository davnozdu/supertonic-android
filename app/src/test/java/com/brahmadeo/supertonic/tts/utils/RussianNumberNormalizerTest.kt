package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class RussianNumberNormalizerTest {
    private val normalizer = RussianNumberNormalizer()

    @Test fun readsWholeIntegersNextToPunctuation() {
        assertEquals("одна тысяча один", normalizer.normalize("1001"))
        assertEquals("одна тысяча сто один", normalizer.normalize("1101"))
        assertEquals("Сумма одна тысяча сто один, затем одна тысяча один.", normalizer.normalize("Сумма 1101, затем 1001."))
        assertEquals("(двадцать пять)!", normalizer.normalize("(25)!"))
        assertEquals("минус сорок два.", normalizer.normalize("-42."))
    }

    @Test fun joinsThousandsGroups() {
        assertEquals("одна тысяча сто один.", normalizer.normalize("1 101."))
        assertEquals("один миллион сто одна тысяча один", normalizer.normalize("1\u202F101\u00A0001"))
    }

    @Test fun readsDecimalAsNumber() {
        assertEquals("три целых четырнадцать сотых.", normalizer.normalize("3,14."))
        assertEquals("три целых четырнадцать сотых", normalizer.normalize("3.14"))
        assertEquals("ноль целых одна сотая", normalizer.normalize("0,01"))
        assertEquals("ноль целых сто одиннадцать тысячных", normalizer.normalize("0,111"))
    }

    @Test fun preservesDatesAndOrdinalSuffixes() {
        assertEquals("12.10.2026", normalizer.normalize("12.10.2026"))
        assertEquals("1-й этаж", normalizer.normalize("1-й этаж"))
    }
    @Test fun agreesWithCountedNounsEvenWithoutLlm() {
        assertEquals("В списке одна тысяча одно имя и одна тысяча сто одна запись.",
            normalizer.normalize("В списке 1001 имя и 1101 запись."))
        assertEquals("двадцать две записи и двадцать одно имя", normalizer.normalize("22 записи и 21 имя"))
        assertEquals("одна тысяча одно и́мя и одна тысяча сто одна за́пись", normalizer.normalize("1001 и́мя и 1101 за́пись"))
    }
    @Test fun preparesNumbersWithoutLosingParagraphsOrCompoundFormats() {
        val prepared = normalizer.prepareForLlm("1001 имя\n1101 запись, 3.14 и 12.10.2026, 10:30, 15%, 5°C, 1-й, 10-15")
        // A calendar date is spelled in its case before the LLM; other compound formats stay digits.
        assertEquals("одна тысяча одно имя\nодна тысяча сто одна запись, 3.14 и двенадцатого октября две тысячи двадцать шестого года, 10:30, 15%, 5°C, 1-й, 10-15", prepared.text)
        assertEquals(listOf("одна тысяча одно", "одна тысяча сто одна", "двенадцатого", "две тысячи двадцать шестого"), prepared.ranges.map { prepared.text.substring(it) })
    }
}
