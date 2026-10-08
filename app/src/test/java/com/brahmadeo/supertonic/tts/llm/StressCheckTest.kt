package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class StressCheckTest {
    private val llm = listOf("Это ещё оттепе́ль, а он нача́л смея́ться. Мы́шкин молча́л.")
    private val offline = listOf("Это ещё о́ттепель, а он на́чал смея́ться. Мышки́н молча́л.")

    @Test fun findsOnlyDisagreements() {
        val d = StressCheck.disputes(llm, offline)
        assertEquals(listOf("оттепе́ль/о́ттепель", "нача́л/на́чал", "Мы́шкин/Мышки́н"), d.map { "${it.llm}/${it.offline}" })
        assertEquals("Это ещё ⟨оттепель⟩, а он начал смеяться.", d[0].sentence)
        assertEquals("⟨Мышкин⟩ молчал.", d[2].sentence)
    }

    @Test fun eachDisputeIsAskedInBothOrdersAndSwitchesOnlyWhenConsistent() {
        val d = StressCheck.disputes(llm, offline)
        assertEquals(listOf("оттепе́ль", "о́ттепель"), StressCheck.items(d, offlineFirst = false)[0].options)
        assertEquals(listOf("о́ттепель", "оттепе́ль"), StressCheck.items(d, offlineFirst = true)[0].options)
        // оттепель: offline both times -> switch; начал: offline then LLM -> keep; name: LLM both times -> keep.
        val fixed = StressCheck.apply(llm, d, listOf(1, 1, 0, 0, 1, 1))
        assertEquals("Это ещё о́ттепель, а он нача́л смея́ться. Мы́шкин молча́л.", fixed.single())
        assertEquals(1, StressCheck.lastOfflineChosen)
    }

    @Test fun anInconsistentJudgeLeavesTheDecisionToTheTieBreaker() {
        val d = StressCheck.disputes(llm, offline)
        // оттепель: judge said 0 and 0 (inconsistent) -> tie-breaker (dictionary) says offline;
        // начал: 1 and 1 (inconsistent), tie-breaker refuses -> LLM stays; name: consistent LLM.
        val fixed = StressCheck.apply(llm, d, listOf(0, 1, 0, 0, 1, 1)) { it.offline == "о́ттепель" }
        assertEquals("Это ещё о́ттепель, а он нача́л смея́ться. Мы́шкин молча́л.", fixed.single())
        assertEquals(0, StressCheck.lastOfflineChosen)
        assertEquals(1, StressCheck.lastTieBreaks)
        assertEquals(1, StressCheck.offlineOrdinal(d[0]))
        assertEquals("оттепель", StressCheck.bareWord(d[0]))
    }

    @Test fun localGemmaYieldsToOfflineExceptExplicitSourceMarks() {
        val gemma = "Ра́скольников жил в Вологде́, а за́мок был стар."
        val offline = "Раско́льников жил в Во́логде, а замо́к был стар."
        assertEquals("Раско́льников жил в Во́логде, а замо́к был стар.",
            StressCheck.preferOffline(gemma, offline, "Раскольников жил в Вологде, а замок был стар."))
        // The book itself marked за́мок: that mark stays.
        assertEquals("Раско́льников жил в Во́логде, а за́мок был стар.",
            StressCheck.preferOffline(gemma, offline, "Раскольников жил в Вологде, а за+мок был стар."))
    }

    @Test fun skipsYoMonosyllablesAndMisalignedFragments() {
        assertEquals(0, StressCheck.disputes(listOf("Всё ещё́ тут."), listOf("Всё е́щё тут.")).size)
        assertEquals(0, StressCheck.disputes(listOf("Оди́н два."), listOf("Оди́н.")).size)
        assertEquals(0, StressCheck.disputes(listOf("Вода́ тут"), listOf("Вода тут")).size)
    }
}
