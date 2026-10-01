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
}
