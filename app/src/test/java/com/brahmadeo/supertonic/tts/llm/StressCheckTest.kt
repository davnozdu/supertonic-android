package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class StressCheckTest {
    // RUAccent's dictionary (homographs left out) and its homograph variants, as on the phone.
    private val dictionary = mapOf("оттепель" to 1, "смеяться" to 3, "молчал" to 2, "мышкин" to 1, "начал" to 1, "хлопок" to 2)
    private val homographs = mapOf("замок" to listOf(1, 2), "начал" to listOf(1, 2))
    private fun plan(llm: String, silero: String, keep: Set<String> = emptySet(), llmAsDictionary: Boolean = false) =
        StressCheck.plan(listOf(llm), listOf(silero), { dictionary[it] }, { homographs[it] }, { _, w -> w in keep }, llmAsDictionary)

    @Test fun agreementIsFinalEvenAgainstTheLlm() {
        val p = plan("Это оттепе́ль, все смея́лись.", "Это +оттепель, все сме+ялись.")
        assertEquals(0, p.disputes.size)
        assertEquals("Это о́ттепель, все смея́лись.", StressCheck.resolve(p, emptyList()).single())
        assertEquals(1, p.agreed); assertEquals(1, p.single)
    }

    @Test fun disagreementsAndHomographsGoToTheJudgeInBothOrders() {
        // хлопок: RUAccent 2, Silero 1; замок: homograph, Silero chose 2; оттепель agreed.
        val p = plan("Хло́пок, за́мок и оттепе́ль.", "Хл+опок, зам+ок и +оттепель.")
        assertEquals(listOf("хлопо́к/хло́пок", "замо́к/за́мок"), p.disputes.map { "${it.first}/${it.second}" }.map { it.lowercase() })
        assertEquals(listOf(false, true), p.disputes.map { it.homograph })
        assertEquals(listOf(1, 2), p.disputes.map { it.silero })
        assertEquals(listOf(1, 1), p.disputes.map { it.llm })
        assertEquals("⟨Хлопок⟩, замок и оттепель.", p.disputes[0].sentence)
        assertEquals(listOf("Хлопо́к", "Хло́пок"), StressCheck.items(p.disputes, secondFirst = false)[0].options)
        assertEquals(listOf("Хло́пок", "Хлопо́к"), StressCheck.items(p.disputes, secondFirst = true)[0].options)
        // хлопок: second both times -> Хло́пок; замок: first both times -> замо́к.
        assertEquals("Хло́пок, замо́к и о́ттепель.", StressCheck.resolve(p, listOf(1, 0, 0, 1)).single())
        assertEquals(1, StressCheck.lastSecondChosen)
    }

    @Test fun aContradictingJudgeFallsBackToTheLlmMarkThenRuaccent() {
        val p = plan("Хло́пок, за́мок.", "Хл+опок, зам+ок.")
        // Both asks picked position 0: the judge contradicts itself. хлопок: the LLM's own mark (1) is an option;
        // замок: the LLM's mark (1) is the second option too.
        assertEquals("Хло́пок, за́мок.", StressCheck.resolve(p, listOf(0, 0, 0, 0)).single())
        assertEquals(2, StressCheck.lastTieBreaks)
        // No LLM at all: a disagreement takes RUAccent's mark, a homograph Silero's.
        val offline = plan("Хлопок, замок.", "Хл+опок, зам+ок.")
        assertEquals("Хлопо́к, замо́к.", StressCheck.resolve(offline, null).single())
        // A name dictionary may decide first.
        assertEquals("Хло́пок, замо́к.", StressCheck.resolve(offline, null) { if (it.first.startsWith("Хл")) 1 else null }.single())
    }

    @Test fun aWordNoDictionaryKnowsKeepsTheLlmMark() {
        val p = plan("Тут ве́рмишель.", "Тут вермишель.")
        assertEquals(1, p.llmOnly)
        assertEquals("Тут ве́рмишель.", StressCheck.resolve(p, null).single())
    }

    @Test fun marksAddedToUnmarkedWordsDoNotShiftLaterDisputes() {
        // Without an LLM every new mark makes a word one character longer; the dispute after them must still land.
        val p = plan("Оттепель, молчал, хлопок.", "+Оттепель, молч+ал, хл+опок.")
        assertEquals("О́ттепель, молча́л, хлопо́к.", StressCheck.resolve(p, null).single())
    }

    @Test fun yoExplicitMonosyllablesAndMisalignedSileroAreSkipped() {
        val p = plan("Ещё о́ттепель, а он был тут.", "Ещё оттеп+ель, а он был тут.", keep = setOf("оттепель"))
        assertEquals(0, p.disputes.size + p.agreed + p.single)
        assertEquals("Ещё о́ттепель, а он был тут.", StressCheck.resolve(p, null).single())
        assertEquals(setOf("за+мок".replace("+", ""), "оттепель"), StressCheck.explicitWords("За+мок и о́ттепель, хлопок."))
        // Silero's text does not line up: only the dictionary speaks (single source).
        val q = plan("Оттепель молчал.", "Оттепель.")
        assertEquals("О́ттепель молча́л.", StressCheck.resolve(q, null).single())
    }

    @Test fun withoutTheDictionaryTheLlmMarkIsTheSecondOpinion() {
        val p = StressCheck.plan(listOf("Вермише́ль тут."), listOf("Верм+ишель тут."), { null }, { null }, llmAsDictionary = true)
        assertEquals(1, p.disputes.size)
        assertEquals("Вермише́ль/Верми́шель", "${p.disputes[0].first}/${p.disputes[0].second}")
    }
}
