package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.*
import org.junit.Test

class PreparedTextValidatorTest {
    @Test fun acceptsStressAndPunctuation() {
        assertEquals("По-прежнему светло́. ты гото́в?", PreparedTextValidator.validate("По-прежнему светло ты готов", "По-прежнему светло́. Ты гото́в?"))
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
        assertNull(PreparedTextValidator.validate("светло", "светло \u0301"))
        assertNull(PreparedTextValidator.validate("светло", "\u0301светло"))
    }
    @Test fun preservesExplicitStressAndCase() {
        assertEquals("СВЕТЛ+О.", PreparedTextValidator.validate("СВЕТЛ+О", "све́тло."))
        assertEquals("Светло́.", PreparedTextValidator.validate("Светло́", "све́тло."))
        assertEquals("Светло́.", PreparedTextValidator.validate("Светло́", "све́тло́."))
        assertEquals("+окно.", PreparedTextValidator.validate("+окно", "окно́."))
        assertEquals("светл+о.", PreparedTextValidator.validate("светл+о", "светл+о."))
    }
    @Test fun preservesArithmeticOperators() {
        assertNull(PreparedTextValidator.validate("1001 + 1101 равно", "1001 1101 ра́вно."))
        assertEquals("1001 + 1101 ра́вно.", PreparedTextValidator.validate("1001 + 1101 равно", "1001 + 1101 ра́вно."))
    }
    @Test fun preservesQuotesAndParagraphs() {
        assertNull(PreparedTextValidator.validate("«окно»", "окно́"))
        assertNull(PreparedTextValidator.validate("Окно\nСветло", "Окно́. Светло́."))
        assertNull(PreparedTextValidator.validate("по-прежнему", "по пре́жнему"))
        assertNull(PreparedTextValidator.validate("он готов", "он-гото́в"))
        assertNull(PreparedTextValidator.validate("Он\nоткрыл окно", "Он откры́л\nокно́"))
        assertNull(PreparedTextValidator.validate("Он сказал «готов»", "Он «сказа́л гото́в»"))
        assertEquals("Он сказа́л: гото́в.", PreparedTextValidator.validate("Он сказал готов", "Он сказа́л: «гото́в»."))
    }
    @Test fun switchesWorkIndependently() {
        assertEquals("светло́ ты гото́в", PreparedTextValidator.validate("светло ты готов", "светло́, ты гото́в?", allowPunctuation = false))
        assertEquals("светло, ты готов?", PreparedTextValidator.validate("светло ты готов", "светло́, ты гото́в?", allowStress = false))
    }
    @Test fun rejectsMissingStressWithoutBreakingPunctuationOnlyMode() {
        assertNull(PreparedTextValidator.validate("В комнате светло", "В комнате светло."))
        assertEquals("В комнате светло.", PreparedTextValidator.validate("В комнате светло", "В комнате светло.", allowStress = false))
        assertEquals("Ты тут?", PreparedTextValidator.validate("Ты тут", "Ты тут?"))
        assertEquals("Светло́!", PreparedTextValidator.validate("Светло́", "Светло́!"))
    }
    @Test fun permitsOnlyNumericGenderWithValuePreserved() {
        val input = com.brahmadeo.supertonic.tts.utils.RussianNumberNormalizer().prepareForLlm("1101 незнакомое")
        assertEquals("одна́ ты́сяча сто одно́ незнако́мое.", validateNumber(input, "одна́ ты́сяча сто одно́ незнако́мое."))
        assertNull(validateNumber(input, "одна́ ты́сяча одно́ незнако́мое."))
        assertNull(validateNumber(input, "одна́ ты́сяча двести одно́ незнако́мое."))
        assertNull(validateNumber(input, "одна́ ты́сяча сто одно́ другое."))
        assertNull(PreparedTextValidator.validate("один незнакомец", "одно́ незнако́мец."))
    }
    private fun validateNumber(input: com.brahmadeo.supertonic.tts.utils.RussianNumberNormalizer.LlmNumbers, output: String) =
        PreparedTextValidator.validate(input.text, output, numberRanges = input.ranges)
}
