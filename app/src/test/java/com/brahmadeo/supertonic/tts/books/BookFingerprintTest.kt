package com.brahmadeo.supertonic.tts.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookFingerprintTest {
    // tools/characters/fingerprint_vectors.json (book_characters.py vectors): Python and Kotlin must agree.
    private val vectors = listOf(
        "— Да, князь, — сказал Рогожин. Он помолчал и прибавил: «Ёлки-палки, вот так встреча!»" to listOf("0c6495ec07a151e0"),
        "Князь Лев Николаевич Мы́шкин вошёл в гостиную; Настасья Филипповна обернулась к нему." to listOf("c06ffb6ae1e5c2a2"),
        "Коротко. Совсем коротко! Но вот это предложение уже достаточно длинное для отпечатка?.." to listOf("6d861cc6a7e8703f"),
        "В 1867 году в Петербурге было сыро и мокро… Поезд подходил к Варшавскому вокзалу." to listOf("a14a8c747b5b2643", "6bad74ec161b2b44"),
        "Mixed text with Latin words, digits 42 and русские слова вместе — проверка нормализации." to listOf("c88e55bf52c8d86b"),
        "Неразрывный пробел после точки тоже граница. Второе предложение начинается сразу после него!" to listOf("e2735df7877ee38e", "256e0da42dd83a6c")
    )

    @Test fun matchesPythonVectors() {
        for ((text, expected) in vectors) assertEquals(text, expected, BookFingerprint.sentences(text).map(BookFingerprint::hex))
    }

    @Test fun hexRoundTrip() {
        for (h in listOf(0L, -1L, Long.MIN_VALUE, 0x0c6495ec07a151e0L)) assertEquals(h, BookFingerprint.parse(BookFingerprint.hex(h)))
    }

    @Test fun packageIsParsedStrictly() {
        val good = """{"format":"mytts-book","version":1,"book":{"title":"Т","author":"А","file_sha256":"f","content_sha256":"c"},
            "scope":"book","sections":[{"id":"s1","title":"Глава","cast":0}],
            "casts":[{"sections":["s1"],"characters":[{"id":"myshkin","name":"Мышкин","gender":"m","speaker":3,"mentions":9,"forms":["князь"],"voice_hint":"ru_igor"}],"other":["Келлер"]}],
            "fingerprint":{"algorithm":"fnv1a64-48","min_letters":24},"fingerprints":{"s1":["0c6495ec07a151e0","ffffffffffffffff"]}}"""
        val pkg = BookPackage.parse(good)
        assertEquals("myshkin", pkg.castOf("s1")!!.characters.single().id)
        assertEquals(listOf(0x0c6495ec07a151e0L, -1L), pkg.fingerprints["s1"])
        for (bad in listOf(good.replace("mytts-book\",", "other\","), good.replace("fnv1a64-48", "sha1"),
                good.replace("\"gender\":\"m\"", "\"gender\":\"x\""), good.replace("\"cast\":0", "\"cast\":5"),
                good.replace("\"s1\":[\"0c", "\"s9\":[\"0c"))) {
            assertTrue("принят некорректный файл: $bad", runCatching { BookPackage.parse(bad) }.isFailure)
        }
    }
}
