package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PreparedTextRepairTest {
    private fun repaired(source: String, answer: String): String? =
        PreparedTextRepair.repair(source, answer)?.let { PreparedTextValidator.validate(source, it.text) }

    @Test fun rewrittenWordFallsBackToSourceWhileOtherStressSurvives() {
        val source = "Уверенно и с сосредоточенной злобой успокоивал её мужчина."
        val answer = "Уве́ренно и с сосредото́ченной зло́бой успока́ивал её мужчи́на."
        assertNull(PreparedTextValidator.validate(source, answer))
        assertEquals("Уве́ренно и с сосредото́ченной зло́бой успокоивал её мужчи́на.", repaired(source, answer))
    }

    @Test fun misplacedMarkAndLostYoAreFixedInPlace() {
        assertEquals("Из о́кон льются пе́сни.", repaired("Из окон льются песни.", "Из о́кон льют́ся пе́сни."))
        assertEquals("Весь нос в кровь — так и тикёт!", repaired("Весь нос в кровь - так и тикёт!", "Весь нос в кровь — так и тике́т!")?.replace("́", ""))
    }

    @Test fun droppedOrAddedWordIsRestoredFromSource() {
        assertNotNull(repaired("Он сидит в своём углу и молчит, ни на кого не глядя.", "Он сиди́т в своём углу́ и молчи́т, ни на кого́ гля́дя."))
        assertEquals("Он сиди́т в своём углу́ и молчи́т.", repaired("Он сидит в своём углу и молчит.", "Он сиди́т в своём углу́ тихо и молчи́т."))
    }

    @Test fun paraphraseStaysRejected() {
        assertNull(PreparedTextRepair.repair("Он сидит в своём углу и молчит.", "Мужчина тихо сидел в углу, ничего не говоря."))
    }
}

class NumeralInflectionTest {
    private val n = com.brahmadeo.supertonic.tts.utils.RussianNumberNormalizer()
    private fun accept(source: String, answer: String): String? {
        val prepared = n.prepareForLlm(source)
        return PreparedTextValidator.validate(prepared.text, answer, numberRanges = prepared.ranges, requireStress = false)
    }
    @Test fun casesAndOrdinalsOfTheSameNumberAreAccepted() {
        assertEquals("Он прочитал сто одну книгу за две недели.", accept("Он прочитал 101 книгу за 2 недели.", "Он прочитал сто одну книгу за две недели."))
        assertEquals("Было без пяти минут двенадцать.", accept("Было без 5 минут 12.", "Было без пяти минут двенадцать."))
        assertNotNull(accept("В 1905 году всё изменилось.", "В одна тысяча девятьсот пятом году всё изменилось."))
        assertNotNull(accept("Прошли 52 версты за 1 сутки.", "Прошли пятьдесят две версты за одни сутки."))
    }
    @Test fun aChangedValueIsStillRejected() {
        assertNull(accept("Он прочитал 101 книгу.", "Он прочитал сто две книги."))
        assertNull(accept("Было без 5 минут 12.", "Было без шести минут двенадцать."))
        assertNull(accept("Он сказал пять раз.", "Он сказал пяти раз."))
    }
}
