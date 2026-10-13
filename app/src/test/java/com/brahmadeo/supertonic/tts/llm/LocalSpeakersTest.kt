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

    @Test fun aLongReplySentByTheSentenceStaysWithItsSpeaker() {
        val s = LocalSpeakers()
        assertEquals("M=— Даром деньги на франкировку письма истратили.", show(s.plan("— Даром деньги на франкировку письма истратили.")))
        assertEquals("M=Гм… по крайней мере, простодушны и искренны, а сие похвально!", show(s.plan("Гм… по крайней мере, простодушны и искренны, а сие похвально!")))
        assertEquals("M=Гм… генерала же Епанчина знаем-с, собственно потому, что человек общеизвестный.",
            show(s.plan("Гм… генерала же Епанчина знаем-с, собственно потому, что человек общеизвестный.")))
        assertEquals("M=Другой доселе в Крыму, а Николай Андреевич, покойник, был человек почтенный и четыре тысячи душ имели-с…",
            show(s.plan("Другой доселе в Крыму, а Николай Андреевич, покойник, был человек почтенный и четыре тысячи душ имели-с…")))
        // Narration: past tense, nothing spoken.
        assertEquals("A=Оказалось, что и это было так: белокурый молодой человек тотчас же объявил.",
            show(s.plan("Оказалось, что и это было так: белокурый молодой человек тотчас же объявил.")))
    }

    @Test fun readestSendsRepliesWithoutTheirLeadingDash() {
        val s = LocalSpeakers()
        assertEquals("A=В конце ноября, в оттепель, часов в девять утра, поезд подходил к Петербургу.",
            show(s.plan("В конце ноября, в оттепель, часов в девять утра, поезд подходил к Петербургу.")))
        // A remark inside the sentence marks its start as a reply.
        assertEquals("M=А позвольте, с кем имею честь… | A=— обратился вдруг угреватый господин.",
            show(s.plan("А\u00a0позвольте, с кем имею честь… — обратился вдруг угреватый господин.").map { it.copy(text = it.text.replace('\u00a0', ' ')) }))
        assertEquals("F=Да, из Швейцарии, | A=— ответила она.", show(s.plan("Да, из Швейцарии, — ответила она.")))
        assertEquals("M=Точно так, его звали Николай Андреевич Павлищев, | A=— и, ответив, молодой человек оглядел господина.",
            show(s.plan("Точно так, его звали Николай Андреевич Павлищев, — и, ответив, молодой человек оглядел господина.")))
        // Spoken language without a remark: the current speaker keeps it.
        assertEquals("M=Зябко?", show(s.plan("Зябко?")))
        assertEquals("M=Да… учился…", show(s.plan("Да… учился…")))
        // Narration is the author's.
        assertEquals("A=Черноволосый присвистнул и захохотал.", show(s.plan("Черноволосый присвистнул и захохотал.")))
        // First-person narration: «я» alone is the narrator, not a reply.
        assertEquals("A=Я шёл домой и думал о ней.", show(LocalSpeakers().plan("Я шёл домой и думал о ней.", firstPerson = true)))
    }

    @Test fun quotesAndPlainTextKeepTheTextIntact() {
        val s = LocalSpeakers()
        assertEquals("M=«Иду!» | A=— крикнул он.", show(s.plan("«Иду!» — крикнул он.")))
        // The same speech in „…“ and "…".
        assertEquals("F=„Иду!“ | A=— крикнула она.", show(LocalSpeakers().plan("„Иду!“ — крикнула она.")))
        assertEquals("M=\"Иду!\" | A=— крикнул он.", show(LocalSpeakers().plan("\"Иду!\" — крикнул он.")))
        val text = "В конце ноября, в оттепель, поезд подходил к Петербургу."
        assertEquals(text, s.plan(text).joinToString("") { it.text })
        assertEquals("A=" + text, show(s.plan(text)))
    }
}
