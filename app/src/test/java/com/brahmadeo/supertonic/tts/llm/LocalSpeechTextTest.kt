package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.*
import org.junit.Test

class LocalSpeechTextTest {
    @Test fun nativeStressBecomesValidatedCommonFormat() {
        val original="По-прежнему светло. Она страдала. Ты готов?"
        val response=LocalSpeechText.response("По-пр+ежнему светл+о. Она страд+ала. Ты гот+ов?")
        assertEquals("По-пре́жнему светло́. Она страда́ла. Ты гото́в?",response)
        assertEquals(response,PreparedTextValidator.validate(original,response))
    }
    @Test fun adapterCannotMakeRewrittenOrMissingWordsAcceptable() {
        assertNull(PreparedTextValidator.validate("Она страдала.",LocalSpeechText.response("Она страд+ала сильно.")))
        assertNull(PreparedTextValidator.validate("По-прежнему светло.",LocalSpeechText.response("По пр+ежнему светл+о.")))
        assertNull(PreparedTextValidator.validate("Она страдала.",LocalSpeechText.response("Она стр+ад+ала.")))
    }
    @Test fun explicitAuthorStressWinsAndMathPlusSurvives() {
        assertEquals("све+тло.",PreparedTextValidator.validate("све+тло.",LocalSpeechText.response("светл+о.")))
        assertEquals("1 + 2",LocalSpeechText.response("1 + 2"))
        assertEquals("Берёза.",LocalSpeechText.response("Берёза."))
    }
    @Test fun removesOnlyOuterTransportFences() {
        assertEquals("«Светло́!»",LocalSpeechText.response("```text\n«Светл+о!»\n```"))
        assertNull(PreparedTextValidator.validate("Светло.",LocalSpeechText.response("Ответ: светл+о.")))
    }
    @Test fun outputBudgetIsBoundedAndTracksInput() {
        assertEquals(256,LocalSpeechText.outputTokens(0))
        assertEquals(376,LocalSpeechText.outputTokens(60))
        assertEquals(2048,LocalSpeechText.outputTokens(Int.MAX_VALUE))
    }
    @Test fun acuteOutputNeedsNoNativeMarkerAdaptation() {
        val original="Зеленый ребенок стоит под елкой. Все ученики пришли."
        val answer="Зелёный ребёнок стои́т под ёлкой. Все ученики́ пришли́."
        assertEquals(answer,LocalSpeechText.response(answer))
        assertEquals(answer,PreparedTextValidator.validate(original,answer))
        assertTrue(LocalSpeechText.prompt(original,"Слева.","Справа.").endsWith("ТЕКСТ:\n$original"))
        assertTrue("U+0301" in LocalSpeechText.instruction(true,true,true,"acute"))
    }
    @Test fun capitalsAreOnlyMarkersWhenAddedToSameSourceWord() {
        val source="Она страдала. Ты готов? По-прежнему светло."
        val response=LocalSpeechText.response("Она страдАла. Ты готОв? По-прЕжнему светлО.",source,"caps")
        assertEquals("Она страда́ла. Ты гото́в? По-пре́жнему светло́.",response)
        assertEquals(response,PreparedTextValidator.validate(source,response))
        assertEquals("ООН и Анна.",LocalSpeechText.response("ООН и Анна.","ООН и Анна.","caps"))
        assertNull(PreparedTextValidator.validate("светло",LocalSpeechText.response("слеблО","светло","caps")))
        assertNull(PreparedTextValidator.validate("страдала",LocalSpeechText.response("стрАдАла","страдала","caps")))
        assertEquals("светл+о",PreparedTextValidator.validate("светл+о",LocalSpeechText.response("свЕтло","светл+о","caps")))
    }
    @Test fun disabledOperationsAreExplicit() {
        val instruction=LocalSpeechText.instruction(false,false,false)
        assertTrue("Не добавляй ударений" in instruction)
        assertTrue("Не меняй знаки" in instruction)
        assertTrue("Не заменяй е на ё" in instruction)
        assertTrue("без JSON" in instruction)
    }
}
