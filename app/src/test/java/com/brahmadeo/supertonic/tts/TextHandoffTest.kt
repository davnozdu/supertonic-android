package com.brahmadeo.supertonic.tts

import com.brahmadeo.supertonic.tts.utils.TextHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextHandoffTest {
    @Test fun shortTextTravelsAsIs() {
        assertEquals("Глава первая.", TextHandoff.wrap("Глава первая."))
        assertEquals("обычный текст", TextHandoff.unwrap("обычный текст"))
        assertNull(TextHandoff.unwrap(null))
    }

    @Test fun longTextIsReplacedByAMarkPerSlot() {
        val chapter = "слово ".repeat(100_000)
        val other = "другое ".repeat(10_000)
        val a = TextHandoff.wrap(chapter)
        val b = TextHandoff.wrap(other, "quick_read")
        assertTrue(a.length < 100)
        assertNotEquals(a, b)
        assertEquals(chapter, TextHandoff.unwrap(a))
        assertEquals(other, TextHandoff.unwrap(b))
    }
}
