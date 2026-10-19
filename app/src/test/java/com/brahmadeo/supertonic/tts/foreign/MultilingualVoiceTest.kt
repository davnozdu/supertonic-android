package com.brahmadeo.supertonic.tts.foreign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultilingualVoiceTest {
    @Test fun twoVoicesSortedBeforeModelVoices() {
        assertTrue(MultilingualVoice.matches("MyTTS Мультиголос"))
        assertTrue(MultilingualVoice.matches("MyTTS Один голос"))
        assertTrue(MultilingualVoice.matches("MyTTS Мультиязычный"))
        assertFalse(MultilingualVoice.matches("ru-supertonic-ru_m5"))
        assertFalse(MultilingualVoice.matches(null))
        assertTrue(MultilingualVoice.single("MyTTS Один голос"))
        assertFalse(MultilingualVoice.single("MyTTS Мультиголос"))
        assertFalse(MultilingualVoice.single(null))
        // Readest sorts a language's voices by name and takes the first: the multi-voice one.
        assertEquals(MultilingualVoice.NAME, listOf("ru-supertonic-dima", "ru-supertonic-ru_m5", MultilingualVoice.SINGLE, MultilingualVoice.NAME).sorted().first())
    }
}
