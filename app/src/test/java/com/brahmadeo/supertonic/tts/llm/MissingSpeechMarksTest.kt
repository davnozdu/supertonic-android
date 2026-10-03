package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.*
import org.junit.Test

class MissingSpeechMarksTest {
    @Test fun fillsGapsButNeverOverwritesLlmStressOrYo() {
        val source="Она страда́ла. Светло, ёлка и все ученики."
        val candidate="Она стр+адала. Светл+о, елка и всё ученик+и."
        assertEquals("Она страда́ла. Светло́, ёлка и все ученики́.",
            MissingSpeechMarks.merge(source,candidate,true,true,setOf("все")))
    }
    @Test fun retainsExactLlmPunctuationParagraphsAndNumbers() {
        val source="«Зеленый ребенок» — 1001.\nЕлка?"
        val candidate="Зелёный ребёнок, 1001! Елка."
        assertEquals("«Зелёный ребёнок» — 1001.\nЕлка?",MissingSpeechMarks.merge(source,candidate,true,true))
    }
    @Test fun ignoresDictionaryRewritesAndMissingWords() {
        assertEquals("Ученики пришли.",MissingSpeechMarks.merge("Ученики пришли.","Учёные пришл+и.",false,true))
        assertEquals("Ученики пришли.",MissingSpeechMarks.merge("Ученики пришли.","пришл+и.",true,true))
    }
    @Test fun switchesDoNotEraseExistingLlmMarks() {
        assertEquals("Светло, зеленый ребёнок.",MissingSpeechMarks.merge("Светло, зеленый ребёнок.","Светл+о, зелёный ребенок.",false,false))
        assertEquals("Светло, зелёный ребёнок.",MissingSpeechMarks.merge("Светло, зеленый ребёнок.","Светл+о, зелёный ребенок.",false,true))
    }
    @Test fun localPartialValidationRetainsMarksAndRejectsSemanticDamage() {
        assertEquals("Светло, ты готов?",PreparedTextValidator.validate("Светло ты готов","Светло, ты готов?",requireStress=false))
        assertEquals("Светло, ты гото́в?",PreparedTextValidator.validate("Светло ты готов","Светло, ты гото́в?",requireStress=false))
        assertNull(PreparedTextValidator.validate("В комнате светло","В комнате светло."))
        assertNull(PreparedTextValidator.validate("1001 имя","1101 имя",requireStress=false))
        assertNull(PreparedTextValidator.validate("Ученики","Учёные",requireStress=false))
    }
}
