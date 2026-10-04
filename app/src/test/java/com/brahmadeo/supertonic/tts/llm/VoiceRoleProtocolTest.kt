package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.*
import org.junit.Test

class VoiceRoleProtocolTest {
    @Test fun malformedParagraphFallsBackWithoutContaminatingNeighbour() {
        val texts=listOf("Ты готов?", "Да, — сказал Иван.")
        val raw="""{"paragraphs":[{"id":0,"segments":[{"start":0,"end":1,"role":"female","confidence":"clear"}]},{"id":1,"segments":[{"start":0,"end":1,"role":"male","confidence":"clear"},{"start":1,"end":4,"role":"author","confidence":"clear"}]}]}"""
        assertNull(VoiceRoleProtocol.parseValidated(raw,texts)[0])
        assertNotNull(VoiceRoleProtocol.parseValidated(raw,texts)[1])
        val plans=VoiceRoleProtocol.parse(raw,texts)
        assertEquals(listOf(VoiceRoleText(texts[0],VoiceRole.AUTHOR)),plans[0])
        assertEquals(listOf(VoiceRole.MALE,VoiceRole.AUTHOR),plans[1].map { it.role })
        texts.forEachIndexed { i,text -> assertEquals(text,plans[i].joinToString("") { it.text }) }
    }
    @Test fun fractionalIndicesAndWrongIdsCannotLoseOrRepeatText() {
        val text="Раз два"
        for (id in listOf("0.0","1","\"0\"")) {
            val raw="""{"paragraphs":[{"id":$id,"segments":[{"start":0,"end":2,"role":"male","confidence":"clear"}]}]}"""
            assertEquals(listOf(VoiceRoleText(text,VoiceRole.AUTHOR)),VoiceRoleProtocol.parse(raw,listOf(text)).single())
        }
    }
    @Test fun missingParagraphsRejectResponse() {
        assertThrows(IllegalArgumentException::class.java) { VoiceRoleProtocol.parse("{\"paragraphs\":[]}",listOf("Текст")) }
    }
}
