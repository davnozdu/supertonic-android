package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.*
import org.junit.Test

class BookTextSpacingTest {
    @Test fun typographyRetainsAllWordsAndParagraphs() {
        for(space in "\u00a0\u202f\u2009\u2002\u2003\u2007\u3000\u200b\t")
            assertEquals("Не раз было.\nДа. Нет!",BookTextSpacing.normalize("Не${space}раз${space}было.\nДа.Нет!"))
    }
    @Test fun numericPunctuationAndAbbreviationsRemainValid() {
        assertEquals("3,14 и 05. 10. 2024; А. С. Пушкин",BookTextSpacing.normalize("3,14 и 05. 10. 2024;А.С.Пушкин"))
        assertEquals("05.10.2024",BookTextSpacing.normalize("05.10.2024"))
        assertEquals("02:01:30",BookTextSpacing.normalize("02:01:30"))
    }
    @Test fun repeatedPreparationDoesNotAddWhitespace() {
        val once=BookTextSpacing.normalize("Не\u202fраз было.Да!\nНет?")
        assertEquals(once,BookTextSpacing.normalize(once))
        assertEquals(1,BookTextSpacing.unusualSpaceCount("не\u202fраз было"))
    }
    @Test fun linksAndEmailAreNotSplitAsSentences() {
        val text="См. https://example.com/a?b=1,2 и github.com/ruvoice; test@example.ru."
        assertEquals(text,BookTextSpacing.normalize(text))
    }
    @Test fun sharedPreparationPreservesWordsInOtherLanguages() {
        for(text in listOf("не раз было", "it was not", "to nebylo", "ce ne fut", "안녕 세상")) {
            assertEquals(text,BookTextSpacing.normalize(text.replace(' ','\u202f')))
        }
    }

    @Test fun canonicalSpellingOfBookCharacters() {
        // Soft hyphens, BOM and joiners split or broke words; composed letters; a grave stress mark.
        assertEquals("Он пил молоко.", BookTextSpacing.normalize("\uFEFFОн пил мо\u00ADлоко."))
        assertEquals("ёж и йод", BookTextSpacing.canonical("е\u0308ж и и\u0306од"))
        assertEquals("молоко\u0301 и ве\u0301ник", BookTextSpacing.canonical("молоко\u0300 и ве\u0300ник"))
        // Latin letters inside a Russian word (a stress typed as ó, look-alikes); foreign words stay for the other engine.
        assertEquals("молоко\u0301, Хорошо, ёлка", BookTextSpacing.canonical("молокó, Xорошо, ëлка"))
        assertEquals("iPhone-ом, Shakespeare и café", BookTextSpacing.canonical("iPhone-ом, Shakespeare и café"))
    }
}
