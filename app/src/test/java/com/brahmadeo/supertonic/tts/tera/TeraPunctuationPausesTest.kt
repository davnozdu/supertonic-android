package com.brahmadeo.supertonic.tts.tera

import org.junit.Assert.*
import org.junit.Test

class TeraPunctuationPausesTest {
    @Test fun shortInterjectionsKeepTheirContextInsteadOfSeparateVowelSynthesis() {
        val parts = TeraPunctuationPauses.synthesisParts(TeraPunctuationPauses.split("Ну, пока он собирался, она ждала. А, понял."))
        assertEquals(listOf("Ну, пока он собирался,", "она ждала.", "А, понял."), parts.map { it.text })
        assertEquals(listOf(180, 420, 420), parts.map { it.pauseMs })
        assertEquals(listOf("Она вернулась. Пока!"), TeraPunctuationPauses.synthesisParts(
            TeraPunctuationPauses.split("Она вернулась. Пока!")).map { it.text })
    }
    @Test fun distinguishesCommaAndSentence() {
        val parts = TeraPunctuationPauses.split("Светло, но прохладно. Ты готов?")
        assertEquals(listOf("Светло,", "но прохладно.", "Ты готов?"), parts.map { it.text })
        assertEquals(listOf(180, 420, 420), parts.map { it.pauseMs })
    }
    @Test fun preservesNumericExpressionsAndDialogue() {
        assertEquals(listOf("— Встреча в 10:30,", "курс 3.14,", "дата 12.10.2026."),
            TeraPunctuationPauses.split("— Встреча в 10:30, курс 3.14, дата 12.10.2026.").map { it.text })
        assertEquals(listOf("Он сказал:", "«Готов!»", "Потом ушёл."),
            TeraPunctuationPauses.split("Он сказал: «Готов!» Потом ушёл.").map { it.text })
    }
    @Test fun doesNotDoubleAnExistingPause() {
        val pcm = ByteArray(44100 / 5 * 2) // 200ms of existing silence
        assertEquals(0, TeraPunctuationPauses.missingSilenceSamples(pcm, 180))
        assertEquals(9702, TeraPunctuationPauses.missingSilenceSamples(pcm, 420))
        pcm[0] = 127; pcm[1] = 127
        assertTrue(TeraPunctuationPauses.missingSilenceSamples(pcm, 420) > 9702)
    }
    @Test fun keepsAbbreviationsInitialsAndCompoundWordsTogether() {
        assertEquals(listOf("А. С. Пушкин жил на ул. Ленина,", "д. 10."),
            TeraPunctuationPauses.split("А. С. Пушкин жил на ул. Ленина, д. 10.").map { it.text })
        assertEquals(listOf("Это,", "т. е. уточнение."),
            TeraPunctuationPauses.split("Это, т. е. уточнение.").map { it.text })
        assertEquals(listOf("По-прежнему светло -", "но холодно."),
            TeraPunctuationPauses.split("По-прежнему светло - но холодно.").map { it.text })
    }
}
