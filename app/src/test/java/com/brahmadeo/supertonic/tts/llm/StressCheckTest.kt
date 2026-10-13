package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class StressCheckTest {
    // RUAccent's dictionary (homographs left out) and its homograph variants, as on the phone.
    private val dictionary = mapOf("оттепель" to 1, "смеяться" to 3, "молчал" to 2, "мышкин" to 1, "начал" to 1, "хлопок" to 2)
    private val homographs = mapOf("замок" to listOf(1, 2), "начал" to listOf(1, 2))
    private fun plan(llm: String, silero: String, keep: Set<String> = emptySet(), llmAsDictionary: Boolean = false) =
        StressCheck.plan(listOf(llm), listOf(silero), { dictionary[it] }, { homographs[it] }, { _, w, _ -> w in keep }, llmAsDictionary)

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

    // The cloud chain: a capitalised word inside a sentence that the names dictionary does not know.
    private fun cloud(llm: String, silero: String, source: String = llm.replace("\u0301", ""), dict: Map<String, Int> = emptyMap(),
                      known: Set<String> = emptySet(), book: Set<String> = emptySet(), variants: Map<String, List<Int>> = emptyMap()) =
        StressCheck.plan(listOf(llm), listOf(silero), { dict[it] }, { variants[it] },
            knownName = { it.lowercase() in known }, addedYo = { _, w -> w in StressCheck.addedYo(source, llm) },
            bookName = { it.lowercase() in book })

    @Test fun dictionariesAgreeingOnACommonWordDoNotOverruleTheLlmOnASurname() {
        // базаров = gen. pl. of базар for both dictionaries; the LLM knows Ба́заров.
        val p = cloud("Пришёл Ба́заров.", "Пришёл Баз+аров.", dict = mapOf("базаров" to 2))
        assertEquals(listOf("Ба́заров/База́ров"), p.disputes.map { "${it.first}/${it.second}" })
        assertEquals("Пришёл Ба́заров.", StressCheck.resolve(p, null).single())
        assertEquals("Пришёл Ба́заров.", StressCheck.resolve(p, listOf(0, 0, 0, 0)).single()) // contradicting judge
        assertEquals("Пришёл База́ров.", StressCheck.resolve(p, listOf(1, 0)).single())       // consistent judge
    }

    @Test fun silerosGuessAloneDoesNotOverruleTheLlmOnAName() {
        val p = cloud("Вошёл Фердыще́нко.", "Вошёл Ферд+ыщенко.")
        assertEquals(1, p.disputes.size)
        assertEquals("Вошёл Фердыще́нко.", StressCheck.resolve(p, null).single())
        // Agreement with the LLM is a plain mark, no question.
        val q = cloud("Вошёл Рого́жин.", "Вошёл Рог+ожин.", dict = mapOf("рогожин" to 2))
        assertEquals(0, q.disputes.size); assertEquals(1, q.agreed)
    }

    @Test fun knownNamesSentenceStartsAndGemmaKeepTheVote() {
        // The names dictionary knows it: the usual vote (here the dictionaries agree, final).
        val known = cloud("Пришёл Ба́заров.", "Пришёл Баз+аров.", dict = mapOf("базаров" to 2), known = setOf("базаров"))
        assertEquals("Пришёл База́ров.", StressCheck.resolve(known, null).single())
        // A sentence start is an ordinary word for all we know...
        val start = cloud("Ба́заров пришёл.", "Баз+аров пришёл.", dict = mapOf("базаров" to 2))
        assertEquals("База́ров пришёл.", StressCheck.resolve(start, null).single())
        // ...unless the prepared book lists it among its characters.
        val book = cloud("Ба́заров пришёл.", "Баз+аров пришёл.", dict = mapOf("базаров" to 2), book = setOf("базаров"))
        assertEquals("Ба́заров пришёл.", StressCheck.resolve(book, null).single())
        // Without knownName (local Gemma) nothing changes.
        assertEquals("Пришёл База́ров.", StressCheck.resolve(plan("Пришёл Ба́заров.", "Пришёл Баз+аров."), null).single())
    }

    @Test fun anLlmYoTheDictionaryDoesNotKnowIsAsked() {
        val p = cloud("Он был рассержённый.", "Он был рассерж+енный.", source = "Он был рассерженный.", dict = mapOf("рассерженный" to 2))
        assertEquals(listOf("рассе́рженный/рассержённый"), p.disputes.map { "${it.first}/${it.second}" })
        assertEquals("Он был рассе́рженный.", StressCheck.resolve(p, null).single())
        assertEquals("Он был рассержённый.", StressCheck.resolve(p, listOf(1, 0)).single())
        // The dictionary has the ё on that vowel (or the ё was in the book): no question.
        assertEquals(0, cloud("Он был чёрный.", "Он был ч+ерный.", source = "Он был черный.", dict = mapOf("черный" to 1)).disputes.size)
        assertEquals(0, cloud("Он был рассержённый.", "Он был рассерж+енный.", source = "Он был рассержённый.",
            dict = mapOf("рассерженный" to 2)).disputes.size)
        // A name keeps the LLM's ё: RUAccent knows the common word only.
        assertEquals(0, cloud("Пришёл Королёв.", "Пришёл Корол+ев.", source = "Пришёл Королев.", dict = mapOf("королев" to 2)).disputes.size)
    }

    @Test fun homographsOfThreeVariantsAndOutsideMarksAreAsked() {
        val v = mapOf("выходите" to listOf(1, 2, 3), "замок" to listOf(1, 2))
        val three = cloud("Выходи́те.", "В+ыходите.", variants = v)
        assertEquals(listOf("Вы́ходите/Выходи́те"), three.disputes.map { "${it.first}/${it.second}" })
        assertEquals("Выходи́те.", StressCheck.resolve(three, null).single())
        // Silero's mark is none of the variants: the LLM's variant against the other one, not a final Silero mark.
        val outside = StressCheck.plan(listOf("Вы́ходите."), listOf("Выход+ите."), { null }, { mapOf("выходите" to listOf(1, 2))[it] })
        assertEquals(listOf("Вы́ходите/Выхо́дите"), outside.disputes.map { "${it.first}/${it.second}" })
    }

    @Test fun anExplicitMarkKeepsOnlyItsOwnOccurrence() {
        val source = "У за́мка нет замка."
        val occurrences = StressCheck.explicitOccurrences(source)
        assertEquals(mapOf("замка" to setOf(0)), occurrences)
        val p = StressCheck.plan(listOf("У за́мка нет за́мка."), listOf("У за́мка нет замк+а."), { null }, { listOf(1, 2) },
            { _, w, n -> occurrences[w]?.contains(n) == true })
        assertEquals(1, p.disputes.size)
        assertEquals("⟨замка⟩".let { "У замка нет $it." }, p.disputes.single().sentence)
    }
}
