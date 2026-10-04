package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.*
import org.junit.Test

class VoiceRolePlanTest {
    @Test fun standaloneDialogueDashNeverTriggersAnotherSynthesis() {
        val text="— Ты готов?"
        assertEquals(listOf(VoiceRoleText(text,VoiceRole.MALE)),VoiceRolePlan.render(text,listOf(
            VoiceRoleRange(0,1,VoiceRole.AUTHOR),VoiceRoleRange(1,3,VoiceRole.MALE))))
    }
    @Test fun exactBookTextSurvivesRoleChangesIncludingWhitespaceAndStress() {
        val text = "  — Ты\u00a0гото́в? — спросила Анна.\n"
        val plan = VoiceRolePlan.render(text, listOf(
            VoiceRoleRange(0,3,VoiceRole.FEMALE), VoiceRoleRange(3,6,VoiceRole.AUTHOR)))!!
        assertEquals(text, plan.joinToString("") { it.text })
        assertEquals(listOf(VoiceRole.FEMALE,VoiceRole.AUTHOR), plan.map { it.role })
    }
    @Test fun gapsOverlapsRepeatsAndMissingSuffixRejectWholePlan() {
        val text="Раз два три четыре"
        for (ranges in listOf(
            listOf(VoiceRoleRange(1,4,VoiceRole.MALE)),
            listOf(VoiceRoleRange(0,2,VoiceRole.MALE),VoiceRoleRange(1,4,VoiceRole.FEMALE)),
            listOf(VoiceRoleRange(0,2,VoiceRole.MALE),VoiceRoleRange(3,4,VoiceRole.FEMALE)),
            listOf(VoiceRoleRange(0,3,VoiceRole.MALE)),
            listOf(VoiceRoleRange(0,5,VoiceRole.MALE)))) assertNull(VoiceRolePlan.render(text,ranges))
    }
    @Test fun uncertainOrCorruptedRoutingUsesAuthorWithoutDroppingWords() {
        val text="Он сказал: да."
        val plan=VoiceRolePlan.render(text,listOf(VoiceRoleRange(0,3,VoiceRole.FEMALE,false)))!!
        assertEquals(listOf(VoiceRoleText(text,VoiceRole.AUTHOR)),plan)
        assertEquals(listOf(VoiceRoleText(text,VoiceRole.AUTHOR)),VoiceRolePlan.safe(text,listOf(VoiceRoleText("да.",VoiceRole.MALE))))
    }
    @Test fun adjacentSameRolesMergeToAvoidArtificialWordPauses() {
        val text="Это не раз было."
        val plan=VoiceRolePlan.render(text,(0..3).map { VoiceRoleRange(it,it+1,VoiceRole.MALE) })!!
        assertEquals(listOf(VoiceRoleText(text,VoiceRole.MALE)),plan)
    }
    @Test fun boundedContextIsIsolatedAndResetOnFlush() {
        val context=VoiceRoleContext(12,2)
        context.append("reader",listOf("А".repeat(100)))
        assertEquals(12,context.get("reader").length)
        assertEquals("",context.get("screenreader"))
        context.append("other",listOf("Б"));context.append("third",listOf("В"))
        assertEquals("",context.get("reader"))
        context.clear("other");assertEquals("",context.get("other"))
        context.clear();assertEquals("",context.get("third"))
    }
}
