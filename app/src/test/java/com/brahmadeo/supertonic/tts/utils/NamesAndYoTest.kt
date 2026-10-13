package com.brahmadeo.supertonic.tts.utils

import com.brahmadeo.supertonic.tts.llm.StressCheck
import org.junit.Assert.assertEquals
import org.junit.Test

class NamesAndYoTest {
    private val names = RussianNames.load(sequenceOf(
        "# test", "семен\tсемён", "семена\tсемёна", "петр\tпётр", "петра\tпетра́", "федор\tфёдор",
        "мышкин\tмы́шкин", "твери\tтвери́", "алена\tалёна", "федора\tфёдора", "волков\tво́лков",
        "алма-ата\tалма-ата́"))

    @Test fun namesGetTheirYoAndStress() {
        RussianNames.initForTests(names)
        assertEquals("Пришёл Семён, за ним Пётр и Фёдор, у Петра́ — Мы́шкин из Твери́.",
            RussianNames.restore(null, "Пришёл Семен, за ним Петр и Федор, у Петра — Мышкин из Твери."))
        // An LLM mark stays on a known name without ё; a name with ё takes the dictionary spelling.
        assertEquals("у Пе́тра и Семён", RussianNames.restore(null, "у Пе́тра и Семе́н"))
        // Lowercase words are not names.
        assertEquals("алена", RussianNames.restore(null, "алена"))
    }

    @Test fun sentenceInitialSemenaDependsOnTheBook() {
        RussianNames.initForTests(names)
        // Before any Семён: "Семена" opening a sentence may be seeds; inside a sentence it is the name.
        assertEquals("Семена взошли. Позвали Семёна.", RussianNames.restore(null, "Семена взошли. Позвали Семена."))
        // The book now has a Семён: a sentence-initial "Семена" is the name as well.
        assertEquals("Семёна баба Катя растила.", RussianNames.restore(null, "Семена баба Катя растила."))
    }

    @Test fun switchesLimitWhatNamesAdd() {
        RussianNames.initForTests(names)
        // Stress off: ё only. ё off: stress only (the ё vowel stressed as е).
        assertEquals("Позвали Петра. Пришёл Семён.", RussianNames.restore(null, "Позвали Петра. Пришёл Семен.", stress = false, yo = true))
        assertEquals("Позвали Петра́. Пришёл Семе́н.", RussianNames.restore(null, "Позвали Петра. Пришёл Семен.", stress = true, yo = false))
        assertEquals("Позвали Петра. Пришёл Семен.", RussianNames.restore(null, "Позвали Петра. Пришёл Семен.", stress = false, yo = false))
    }

    @Test fun forgetEndsTheBook() {
        RussianNames.initForTests(names)
        RussianNames.restore(null, "Позвали Семена.")
        RussianNames.forget()
        assertEquals("Семена взошли весной.", RussianNames.restore(null, "Семена взошли весной."))
    }

    @Test fun overlayAndHintUseTheDictionary() {
        RussianNames.initForTests(names)
        assertEquals("Вошёл Мы́шкин", RussianNames.overlay(null, "Вошёл Мышки́н"))
        assertEquals(listOf("Семён", "Петра́"), RussianNames.hint(null, listOf("пришли Семен и Петра ждали")))
        assertEquals(2, RussianNames.ordinal("Петра"))
        assertEquals(2, RussianNames.ordinal("Семен"))
    }

    @Test fun safeYoFromTheTable() {
        val table = mapOf("черный" to "ч+ёрный", "ребенок" to "ребёнок", "все" to "всё")
        assertEquals("Чёрный кот и ребёнок, все спят.",
            YoRestore.apply("Черный кот и ребе́нок, все спят.", setOf("все")) { table[it] })
    }

    @Test fun agreedHomographsAreAskedToo() {
        // Silero agrees with the LLM on both homographs ("звучал о́рган" is wrong): RUAccent lists them, so the judge
        // is asked anyway; with no dictionary mark the homograph's first option is Silero's.
        val llm = listOf("Звучал о́рган, а он уже́ ушёл.")
        val variants = mapOf("орган" to listOf(1, 2), "уже" to listOf(1, 2))
        val p = StressCheck.plan(llm, listOf("Звучал +орган, а он уж+е ушёл."), { null }, { variants[it] })
        assertEquals(listOf("о́рган/орга́н", "уже́/у́же"), p.disputes.map { "${it.first}/${it.second}" })
        assertEquals(listOf(true, true), p.disputes.map { it.homograph })
        assertEquals("Звучал ⟨орган⟩, а он уже ушёл.", p.disputes[0].sentence)
        // An inconsistent judge: the LLM's own mark stays (it is one of the two options).
        assertEquals(llm, StressCheck.resolve(p, listOf(0, 0, 0, 0)))
        // A consistent one switches: орган both times second, уже both times first.
        assertEquals(listOf("Звучал орга́н, а он уже́ ушёл."), StressCheck.resolve(p, listOf(1, 0, 0, 1)))
    }

    @Test fun explicitAndOtherVowelMarksSurviveTheNamesDictionary() {
        RussianNames.initForTests(names)
        // The LLM read «Федо́ра Егоровна» (a woman): not the dictionary's genitive «Фёдора».
        assertEquals("Пришла Федо́ра Егоровна.", RussianNames.restore(null, "Пришла Федо́ра Егоровна."))
        // A mark of the book itself stays, even on the ё vowel; only its occurrence.
        val source = "Позвали Фе́дора и Федора."
        assertEquals("Позвали Фе́дора и Фёдора.", RussianNames.restore(null, source, explicit = StressCheck.explicitOccurrences(source)))
        // Without ё the name is stressed at once (the offline chain no longer drops it later).
        assertEquals("Позвали Фе́дора.", RussianNames.restore(null, "Позвали Федора.", stress = true, yo = false))
    }

    @Test fun capitalsAndHyphenatedNames() {
        RussianNames.initForTests(names)
        assertEquals("ГЛАВА ПЕРВАЯ. ФЁДОР", RussianNames.restore(null, "ГЛАВА ПЕРВАЯ. ФЕДОР"))
        assertEquals("Уехал в Алма-Ату, а потом в Алма-Ата́.",
            RussianNames.restore(null, "Уехал в Алма-Ату, а потом в Алма-Ата."))
    }

    @Test fun overlayReplacesSilerosPlusMarks() {
        RussianNames.initForTests(names)
        assertEquals("Пришёл Во́лков, за ним волк+ов стая.", RussianNames.overlay(null, "Пришёл Волк+ов, за ним волк+ов стая."))
        assertEquals("Вошёл Мы́шкин", RussianNames.overlay(null, "Вошёл Мышк+ин"))
    }

    @Test fun quotesAndDashesOpenSentences() {
        RussianNames.initForTests(names)
        assertEquals("— Семена взошли. Он сказал: «Семена взошли».", RussianNames.restore(null, "— Семена взошли. Он сказал: «Семена взошли»."))
    }

    @Test fun safeYoKeepsTheBooksOwnMark() {
        val table = mapOf("черный" to "ч+ёрный")
        val source = "Че́рный и черный."
        assertEquals("Че́рный и чёрный.", YoRestore.apply(source, emptySet(), StressCheck.explicitOccurrences(source)) { table[it] })
        // An LLM mark on another vowel of a certain-ё word goes.
        assertEquals("чёрный", YoRestore.apply("черны́й", emptySet()) { table[it] })
    }
}
