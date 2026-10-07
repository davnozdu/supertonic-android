package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class StressCheckTest {
    private val llm = listOf("Это ещё оттепе́ль, а он нача́л смея́ться. Мы́шкин молча́л.")
    private val offline = listOf("Это ещё о́ттепель, а он на́чал смея́ться. Мышки́н молча́л.")

    @Test fun findsOnlyDisagreements() {
        val d = StressCheck.disputes(llm, offline)
        assertEquals(listOf("оттепе́ль/о́ттепель", "нача́л/на́чал", "Мы́шкин/Мышки́н"), d.map { "${it.llm}/${it.offline}" })
        assertEquals("Это ещё оттепель, а он начал смеяться.", d[0].sentence)
        assertEquals("Мышкин молчал.", d[2].sentence)
    }

    @Test fun eachDisputeIsAskedInBothOrdersAndSwitchesOnlyWhenConsistent() {
        val d = StressCheck.disputes(llm, offline)
        val items = StressCheck.items(d)
        assertEquals(6, items.size)
        assertEquals(listOf("оттепе́ль", "о́ттепель"), items[0].options)
        assertEquals(listOf("о́ттепель", "оттепе́ль"), items[3].options)
        // оттепель: offline both times -> switch; начал: offline then LLM -> keep; name: LLM both times -> keep.
        val fixed = StressCheck.apply(llm, d, listOf(1, 1, 0, 0, 1, 1))
        assertEquals("Это ещё о́ттепель, а он нача́л смея́ться. Мы́шкин молча́л.", fixed.single())
        assertEquals(1, StressCheck.lastOfflineChosen)
    }

    @Test fun skipsYoMonosyllablesAndMisalignedFragments() {
        assertEquals(0, StressCheck.disputes(listOf("Всё ещё́ тут."), listOf("Всё е́щё тут.")).size)
        assertEquals(0, StressCheck.disputes(listOf("Оди́н два."), listOf("Оди́н.")).size)
        assertEquals(0, StressCheck.disputes(listOf("Вода́ тут"), listOf("Вода тут")).size)
    }
}
