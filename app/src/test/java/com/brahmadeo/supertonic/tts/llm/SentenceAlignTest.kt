package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceAlignTest {
    private val original = "В 1867 году князь приехал в Петербург. — Кто вы? — спросил Рогожин. Ответа не было…"
    private val prepared = "В ты́сяча восемьсо́т шестьдеся́т седьмо́м году́ князь прие́хал в Петербу́рг. — Кто́ вы? — спроси́л Рого́жин. Отве́та не́ было…"

    @Test fun sentencesKeepTheirStressAndPunctuation() {
        val a = SentenceAlign.Aligned(original, prepared)
        assertEquals("В ты́сяча восемьсо́т шестьдеся́т седьмо́м году́ князь прие́хал в Петербу́рг.", a.cut("В 1867 году князь приехал в Петербург.")!!.text)
        assertEquals("— Кто́ вы?", a.cut("— Кто вы?")!!.text)
        assertEquals("— спроси́л Рого́жин.", a.cut("— спросил Рогожин.")!!.text)
        assertEquals("Отве́та не́ было…", a.cut("Ответа не было…")!!.text)
    }

    @Test fun anotherParagraphIsNotFound() {
        assertNull(SentenceAlign.Aligned(original, prepared).cut("Совсем другое предложение здесь."))
    }

    @Test fun planIsClippedToTheSentence() {
        val a = SentenceAlign.Aligned(original, prepared)
        val narration = "В ты́сяча восемьсо́т шестьдеся́т седьмо́м году́ князь прие́хал в Петербу́рг. "
        val reply = "— Кто́ вы? "
        val rest = prepared.removePrefix(narration).removePrefix(reply)
        val plan = listOf(VoiceRoleText(narration, VoiceRole.AUTHOR), VoiceRoleText(reply, VoiceRole.MALE, "rogozhin"),
            VoiceRoleText(rest, VoiceRole.AUTHOR))
        val cut = a.cut("— Кто вы?")!!
        assertEquals(listOf(VoiceRoleText("— Кто́ вы?", VoiceRole.MALE, "rogozhin")), SentenceAlign.clipPlan(prepared, plan, cut))
        assertEquals(emptyList<VoiceRoleText>(), SentenceAlign.clipPlan(prepared + "x", plan, cut))
    }
}
