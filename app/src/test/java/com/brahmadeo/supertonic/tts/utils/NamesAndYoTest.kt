package com.brahmadeo.supertonic.tts.utils

import com.brahmadeo.supertonic.tts.llm.StressCheck
import org.junit.Assert.assertEquals
import org.junit.Test

class NamesAndYoTest {
    private val names = RussianNames.load(sequenceOf(
        "# test", "семен\tсемён", "семена\tсемёна", "петр\tпётр", "петра\tпетра́", "федор\tфёдор",
        "мышкин\tмы́шкин", "твери\tтвери́", "алена\tалёна"))

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
        val llm = listOf("Звучал о́рган, а он уже́ ушёл.")
        val variants = mapOf("орган" to listOf("о́рган", "орга́н"), "уже" to listOf("уже́", "у́же"))
        val d = StressCheck.homographs(llm, emptyList()) { variants[it] }
        assertEquals(listOf("о́рган/орга́н", "уже́/у́же"), d.map { "${it.llm}/${it.offline}" })
        assertEquals("Звучал ⟨орган⟩, а он уже ушёл.", d[0].sentence)
        // Homographs never take the dictionary tie-break: an inconsistent judge keeps the LLM mark.
        assertEquals(llm, StressCheck.apply(llm, d, listOf(0, 0, 0, 0)) { true })
    }
}
