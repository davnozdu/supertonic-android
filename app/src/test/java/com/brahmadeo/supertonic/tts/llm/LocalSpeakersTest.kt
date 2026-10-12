package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSpeakersTest {
    private fun show(parts: List<VoiceRoleText>) = parts.joinToString(" | ") { "${it.role.name.first()}${it.character?.let { c -> ":$c" } ?: ""}=${it.text.trim()}" }

    @Test fun dialogueWithRemarksAndAlternation() {
        val s = LocalSpeakers()
        // A remark names the gender; the remark itself is the author's.
        assertEquals("M=— Очень, | A=— ответил сосед с чрезвычайною готовностью, | M=— и заметьте, это еще оттепель.",
            show(s.plan("— Очень, — ответил сосед с чрезвычайною готовностью, — и заметьте, это еще оттепель.")))
        assertEquals("F=— Да, из Швейцарии. | A=— сказала она.", show(s.plan("— Да, из Швейцарии. — сказала она.")))
        // No remark: the other participant.
        assertEquals("M=— Фью!", show(s.plan("— Фью!")))
        // Continuation of the same line (exclamation, addressing «вас»).
        assertEquals("M=Эк ведь вас!..", show(s.plan("Эк ведь вас!..")))
        // Narration ends the speech.
        assertEquals("A=Черноволосый присвистнул и захохотал.", show(s.plan("Черноволосый присвистнул и захохотал.")))
        assertEquals("A=Черноволосый присвистнул.", show(s.plan("Черноволосый присвистнул.")))
    }

    @Test fun charactersOfThePreparedBookAndFirstPerson() {
        val cast = listOf(LocalSpeakers.Person("rogozhin", "m", listOf("Рогожин", "Рогожина", "Парфен")),
            LocalSpeakers.Person("nastasya", "f", listOf("Настасья Филипповна", "Настасья")))
        val s = LocalSpeakers()
        assertEquals("M:rogozhin=— Тьфу тебя! | A=— сплюнул Рогожин.", show(s.plan("— Тьфу тебя! — сплюнул Рогожин.", cast)))
        assertEquals("F:nastasya=— Ну что ж! | A=— воскликнула Настасья.", show(s.plan("— Ну что ж! — воскликнула Настасья.", cast)))
        assertEquals("F:nastasya=— Нет! | A=— сказала Настасья Филипповна.", show(LocalSpeakers().plan("— Нет! — сказала Настасья Филипповна.",
            listOf(LocalSpeakers.Person("nastasya", "f", listOf("Настасья Филипповна"))))))
        // Next line without a remark: the other one of the two.
        assertEquals("M:rogozhin=— Не дам.", show(s.plan("— Не дам.", cast)))
        // First-person narration: «— сказал я» is the narrator, read by the author's voice.
        assertEquals("A=— Иду, — сказал я.", show(LocalSpeakers().plan("— Иду, — сказал я.", firstPerson = true)))
    }

    @Test fun quotesAndPlainTextKeepTheTextIntact() {
        val s = LocalSpeakers()
        assertEquals("M=«Иду!» | A=— крикнул он.", show(s.plan("«Иду!» — крикнул он.")))
        val text = "В конце ноября, в оттепель, поезд подходил к Петербургу."
        assertEquals(text, s.plan(text).joinToString("") { it.text })
        assertEquals("A=" + text, show(s.plan(text)))
    }
}
