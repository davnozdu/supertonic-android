package com.brahmadeo.supertonic.tts.books.prepare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Правило голосовых звуков: слово названо прямо — событие ставится; отрицание не берётся. */
class VoiceSoundsTest {

    private fun words(text: String) = text.split(" ")

    @Test
    fun cryingIsFoundInTheWordThatNamesIt() {
        val w = words("Он отпрянул и разревелся")
        assertEquals(3, VoiceSounds.index(w, "cry"))
    }

    @Test
    fun punctuationAndCaseDoNotHideTheWord() {
        assertEquals(0, VoiceSounds.index(words("Заплакал,"), "cry"))
        assertEquals(1, VoiceSounds.index(words("мальчик заплакала"), "cry"))
    }

    @Test
    fun negationIsSkipped() {
        assertNull(VoiceSounds.index(words("он не заплакал"), "cry"))
        assertNull(VoiceSounds.index(words("ни разу не плакал"), "cry"))
    }

    @Test
    fun otherVoiceSoundsAreFound() {
        assertEquals(1, VoiceSounds.index(words("Она засмеялась тихо"), "laugh_soft"))
        assertEquals(1, VoiceSounds.index(words("кто-то крикнул вдалеке"), "shout"))
        assertEquals(1, VoiceSounds.index(words("он прошептал имя"), "whisper"))
        assertEquals(1, VoiceSounds.index(words("Она вздохнула"), "sigh"))
    }

    @Test
    fun plainWordIsNotAnEvent() {
        assertNull(VoiceSounds.index(words("крик петуха за окном"), "shout"))
        assertNull(VoiceSounds.index(words("слёзы на глазах"), "cry"))
    }
}
