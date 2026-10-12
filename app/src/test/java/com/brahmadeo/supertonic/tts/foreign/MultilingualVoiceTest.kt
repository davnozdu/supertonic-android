package com.brahmadeo.supertonic.tts.foreign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultilingualVoiceTest {
    @Test fun namesRoundTripAndSortBeforeModelVoices() {
        val ru = MultilingualVoice.name("ru")
        assertEquals("ru-multilingual-MyTTS", ru)
        assertTrue(MultilingualVoice.matches(ru))
        assertEquals("de", MultilingualVoice.language(MultilingualVoice.name("de")))
        assertFalse(MultilingualVoice.matches("ru-supertonic-ru_m5"))
        assertFalse(MultilingualVoice.matches("-multilingual-MyTTS"))
        assertFalse(MultilingualVoice.matches(null))
        // Readest sorts a language's voices by name and takes the first.
        assertEquals(ru, listOf("ru-supertonic-dima", "ru-supertonic-ru_m5", ru).sorted().first())
        // Parsed as a plain language tag, like the model voices.
        assertEquals("ru", java.util.Locale.forLanguageTag(ru).toLanguageTag())
    }
}
