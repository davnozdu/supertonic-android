package com.brahmadeo.supertonic.tts.foreign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForeignLanguagesTest {
    @Test fun storedListWithEnginesRoundTrips() {
        val entries = ForeignLanguages.parse("en=com.google.android.tts; de=com.example.tts;cs=;ru=x;en=dup", "old", "auto")
        assertEquals(listOf("en" to "com.google.android.tts", "de" to "com.example.tts", "cs" to ""), entries.map { it.language to it.engine })
        assertEquals("en=com.google.android.tts;de=com.example.tts;cs=", ForeignLanguages.serialize(entries))
        assertEquals(emptyList<ForeignLanguages.Entry>(), ForeignLanguages.parse("", "x", "auto"))
        assertEquals(listOf(ForeignLanguages.Entry("en", "eng.pkg")), ForeignLanguages.parse(null, "eng.pkg", "en"))
    }

    @Test fun readerTagThenCzechThenDetectorThenFirst() {
        val connected = listOf("en", "de", "cs")
        assertEquals("de", ForeignLanguages.pick("Guten Tag", "de-DE", connected))
        assertEquals("de", ForeignLanguages.pick("Guten Tag", "deu", connected))
        // Russian or unknown reader tags fall through to the text itself.
        assertEquals("cs", ForeignLanguages.pick("Děkuji", "ru", connected))
        assertEquals("de", ForeignLanguages.pick("Ich habe keine Zeit", "ru", connected, detected = listOf("fr", "de")))
        assertEquals("en", ForeignLanguages.pick("Some words", "fr", connected, detected = listOf("fr")))
        assertEquals("de", ForeignLanguages.pick("Anything", "ru", listOf("de")))
        assertNull(ForeignLanguages.pick("Anything", "en", emptyList()))
    }
}
