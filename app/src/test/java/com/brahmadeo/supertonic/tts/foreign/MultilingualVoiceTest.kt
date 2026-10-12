package com.brahmadeo.supertonic.tts.foreign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultilingualVoiceTest {
    @Test fun oneVoiceSortedBeforeModelVoices() {
        assertTrue(MultilingualVoice.matches("MyTTS Мультиязычный"))
        assertFalse(MultilingualVoice.matches("ru-supertonic-ru_m5"))
        assertFalse(MultilingualVoice.matches(null))
        // Readest sorts a language's voices by name and takes the first.
        assertEquals(MultilingualVoice.NAME, listOf("ru-supertonic-dima", "ru-supertonic-ru_m5", MultilingualVoice.NAME).sorted().first())
    }
}
