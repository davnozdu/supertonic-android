package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.*
import org.junit.Test

class PreparedTextValidatorTest {
    @Test fun acceptsStressAndPunctuation() {
        assertEquals("По-прежнему светло́. Ты гото́в?", PreparedTextValidator.validate("По-прежнему светло ты готов", "По-прежнему светло́. Ты гото́в?"))
    }
    @Test fun rejectsChangedWordsAndNumbers() {
        assertNull(PreparedTextValidator.validate("Открой окно", "Открой дверь."))
        assertNull(PreparedTextValidator.validate("Курс 3.14 рубля", "Курс 3,14 ру́бля."))
        assertNull(PreparedTextValidator.validate("Сумма 100 рублей", "Сумма 1000 рубле́й."))
    }
    @Test fun rejectsMalformedStress() {
        assertNull(PreparedTextValidator.validate("светло", "све́тло́"))
        assertNull(PreparedTextValidator.validate("светло", "свет́ло"))
        assertNull(PreparedTextValidator.validate("светло", "светл+о"))
    }
    @Test fun preservesExplicitStressAndCase() {
        assertEquals("СВЕТЛ+О.", PreparedTextValidator.validate("СВЕТЛ+О", "све́тло."))
        assertEquals("Светло́.", PreparedTextValidator.validate("Светло́", "све́тло."))
    }
    @Test fun switchesWorkIndependently() {
        assertEquals("светло́ ты гото́в", PreparedTextValidator.validate("светло ты готов", "светло́, ты гото́в?", allowPunctuation = false))
        assertEquals("светло, ты готов?", PreparedTextValidator.validate("светло ты готов", "светло́, ты гото́в?", allowStress = false))
    }
}
