package com.brahmadeo.supertonic.tts.tera

import org.junit.Assert.assertEquals
import org.junit.Test

class TeraTextPreparationTest {
    @Test fun preservesCommaAndSentenceBoundaries() {
        assertEquals("Да, конечно. Потом уйдём! Правда?", TeraTextPreparation.punctuation("Да,конечно.Потом уйдём!Правда?"))
    }

    @Test fun dashInsideSentenceBecomesPauseCue() {
        assertEquals("Он пришёл, я ушёл.", TeraTextPreparation.punctuation("Он пришёл—я ушёл."))
        assertEquals("Сначала слово, потом другое.", TeraTextPreparation.punctuation("Сначала слово – потом другое."))
    }

    @Test fun dialogueDashDoesNotAddCommaAfterSentenceEnd() {
        assertEquals("Привет! сказал он.", TeraTextPreparation.punctuation("— Привет! — сказал он."))
    }

    @Test fun preservesHyphensAndDecimalNumbers() {
        assertEquals("По-прежнему, по-русски: 3,14 и 1.5.", TeraTextPreparation.punctuation("По-прежнему,по-русски: 3,14 и 1.5."))
    }

    @Test fun ellipsisAndSmartQuotesUseSupportedCharacters() {
        assertEquals("\"Подожди...\"", TeraTextPreparation.punctuation("“Подожди…”"))
    }

    private val dictionary = mapOf("светло" to "св+етло", "готов" to "г+отов",
        "потом" to "п+отом", "улыбнулся" to "улыбн+улся")
    private fun stress(text: String) = TeraTextPreparation.stress(text, dictionary::get,
        mapOf("все" to "всё", "елка" to "ёлка"),
        setOf("светло", "готов", "потом"), setOf("все"))

    @Test fun dictionaryDoesNotForceWrongHomographStress() {
        assertEquals("Потом он готов. Светло, улыбн+улся.", stress("Потом он готов. Светло, улыбнулся."))
    }

    @Test fun explicitStressStillWinsForAmbiguousWords() {
        assertEquals("светл+о гот+ов пот+ом", stress("светло\u0301 гот+ов пото\u0301м"))
    }

    @Test fun ambiguousYoReplacementIsNotForced() {
        assertEquals("Все ёлка.", stress("Все елка."))
    }
}
