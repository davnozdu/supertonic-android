package com.brahmadeo.supertonic.tts.llm
import org.junit.Assert.*
import org.junit.Test
class VoiceRoleDefaultsTest {
    @Test fun kokoroUsesItsTwoFemaleAndOneMaleVoices() {
        val voices = listOf("sveta", "masha", "dima")
        assertEquals(listOf("sveta", "dima", "masha"), VoiceRole.entries.map {
            VoiceRoleDefaults.select("kokoro_ru_v2", it, "sveta", voices)
        })
        assertEquals("sveta", VoiceRoleDefaults.select("kokoro_ru_v2", VoiceRole.FEMALE, "masha", voices))
    }
    @Test fun sileroAllThreeRolesUseDifferentInstalledVoices() {
        val voices=listOf("aidar","baya","kseniya","eugene","xenia")
        for(author in voices) {
            val choices=VoiceRole.entries.map { VoiceRoleDefaults.select("silero_v5_5_ru",it,author,voices) }
            assertEquals(3,choices.distinct().size)
            assertTrue(choices.all { it in voices })
            assertEquals(author,choices.first())
        }
    }
    @Test fun teraFemaleNarratorDoesNotReuseFemaleDialogueVoice() {
        val voices=listOf("ru_f1","ru_f2","ru_m1","ru_m5")
        for(author in voices) assertEquals(3,VoiceRole.entries.map { VoiceRoleDefaults.select("teratts_v2",it,author,voices) }.distinct().size)
    }
}
