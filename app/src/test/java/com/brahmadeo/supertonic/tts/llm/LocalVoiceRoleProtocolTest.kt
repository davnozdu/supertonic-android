package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.*
import org.junit.Test

class LocalVoiceRoleProtocolTest {
    @Test fun fixedFragmentsPreserveEveryLetterAndWhitespace() {
        val text="Павел подошёл к окну.\n— Как красиво! — сказала Ольга.\n— Да, — ответил Павел."
        val (_,pieces)=LocalVoiceRoleProtocol.prompt(listOf(text),"")
        assertEquals(text,pieces.flatten().joinToString(""))
        assertEquals(5,pieces.flatten().size)
        val plan=LocalVoiceRoleProtocol.parse("АЖАМА",pieces).single()
        assertEquals(text,plan.joinToString("") { it.text })
        assertEquals(listOf(VoiceRole.AUTHOR,VoiceRole.FEMALE,VoiceRole.AUTHOR,VoiceRole.MALE,VoiceRole.AUTHOR),plan.map { it.role })
    }
    @Test fun hyphenatedWordsNeverBecomeSeparateVoiceFragments() {
        val text="Он по-прежнему думал: когда-нибудь всё наладится."
        assertEquals(listOf(text),LocalVoiceRoleProtocol.fragments(text))
    }
    @Test fun incorrectLabelCountAndRewrittenTextAreRejected() {
        val pieces=listOf(listOf("Да. ","Автор."))
        for (answer in listOf("М","МАА","Мужчина и автор","Да. Автор.")) {
            assertThrows(IllegalArgumentException::class.java) { LocalVoiceRoleProtocol.parse(answer,pieces) }
        }
    }
    @Test fun adjacentEqualLabelsProduceOneNaturalFragment() {
        val plan=LocalVoiceRoleProtocol.parse("[А, А]",listOf(listOf("Он решил — ","пусть идёт."))).single()
        assertEquals(listOf(VoiceRoleText("Он решил — пусть идёт.",VoiceRole.AUTHOR)),plan)
    }
}
