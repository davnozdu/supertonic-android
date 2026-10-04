package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class VoiceRoleRoutingTest {
    private val texts = listOf("Автор смотрит в окно.", "— Да, — сказал Иван.", "— Нет! — сказала Анна.")
    private fun roles(input: List<String>) = input.map { text -> listOf(VoiceRoleText(text,
        when { "Иван" in text -> VoiceRole.MALE; "Анна" in text -> VoiceRole.FEMALE; else -> VoiceRole.AUTHOR })) }

    @Test fun networkLossUsesLocalAndRecoveredCloudUpgradesQueuedText() {
        val handoff = PreparedSpeechHandoff()
        val source = texts.joinToString("\n")
        val local = VoiceRoleRouting.resolve(texts, listOf("ollama", "local"), "", { true }, { provider, input, _ ->
            if (provider == "ollama") throw IOException("Network unavailable")
            input.map { listOf(VoiceRoleText(it, VoiceRole.AUTHOR)) }
        })
        assertEquals(listOf("local", "local", "local"), local.providers)
        val localPlan = local.plans.flatMapIndexed { i, plan -> plan!! + if (i < texts.lastIndex) listOf(VoiceRoleText("\n", VoiceRole.AUTHOR)) else emptyList() }
        handoff.put(handoff.token(), source, source, true, localPlan)
        val recovered = VoiceRoleRouting.resolve(texts, listOf("ollama", "local"), "", { true }, { provider, input, _ ->
            assertEquals("ollama", provider); roles(input)
        })
        assertEquals(listOf("ollama", "ollama", "ollama"), recovered.providers)
        val plan = recovered.plans.flatMapIndexed { i, p -> p!! + if (i < texts.lastIndex) listOf(VoiceRoleText("\n", VoiceRole.AUTHOR)) else emptyList() }
        assertTrue(handoff.replace(source, PreparedSpeechText(source, true, plan)))
        val delivered = handoff.takePrepared(source)!!
        assertEquals(source, delivered.voicePlan.joinToString("") { it.text })
        assertEquals(setOf(VoiceRole.AUTHOR, VoiceRole.MALE, VoiceRole.FEMALE), delivered.voicePlan.map { it.role }.toSet())
        assertFalse(handoff.replace(source, PreparedSpeechText(source, true, localPlan)))
        assertNull(handoff.takePrepared(source))
    }
    @Test fun malformedNeighbourRetriesLocallyWithoutOverwritingValidCloudRoles() {
        val calls = mutableListOf<List<String>>()
        val result = VoiceRoleRouting.resolve(texts, listOf("gemini", "local"), "", { true }, { provider, input, _ ->
            if (provider == "gemini") roles(input).mapIndexed { i, p -> if (i == 1) null else p }
            else { calls += input; roles(input) }
        })
        assertEquals(listOf(listOf(texts[1])), calls)
        assertEquals(listOf("gemini", "local", "gemini"), result.providers)
    }
    @Test fun largeParagraphsRespectLocalLimitsAndPreserveEveryCharacter() {
        val text = ("Автор. — Да! — сказала Анна.\n").repeat(180)
        var calls = 0
        val result = VoiceRoleRouting.resolve(listOf(text), listOf("local"), "Предыдущий текст", { true }, { _, parts, before ->
            calls++; assertTrue(parts.sumOf { it.length } <= 1000)
            assertTrue(parts.sumOf { LocalVoiceRoleProtocol.fragments(it).size } <= 24)
            assertTrue(before.length <= 600)
            parts.map { listOf(VoiceRoleText(it, VoiceRole.AUTHOR)) }
        })
        assertTrue(calls > 1)
        assertEquals(text, result.plans.single()!!.joinToString("") { it.text })
    }
    @Test fun failedCallsRemainUnresolvedRatherThanBecomingPermanentAuthorResults() {
        val failed = VoiceRoleRouting.resolve(texts, listOf("ollama", "local"), "", { true }, { _, _, _ -> throw IOException() })
        assertTrue(failed.plans.all { it == null })
        assertTrue(failed.providers.all { it == null })
        val after = VoiceRoleRouting.resolve(texts, listOf("ollama"), "", { true }, { _, input, _ -> roles(input) })
        assertEquals(VoiceRole.FEMALE, after.plans[2]!!.single().role)
    }
    @Test fun completeParagraphsAreDeliveredBeforeLaterSlowRequests() {
        var delivered = false
        VoiceRoleRouting.resolve(listOf("Первый абзац.", "Второй ".repeat(400)), listOf("ollama"), "", { true },
            request = { _, parts, _ ->
                if (parts.none { it == "Первый абзац." }) assertTrue(delivered)
                parts.map { listOf(VoiceRoleText(it, VoiceRole.AUTHOR)) }
            }, resolved = { i, _, _ -> if (i == 0) delivered = true })
        assertTrue(delivered)
    }
    @Test fun acceptedParagraphRolesSurviveSlowTextRetriesAndAreNotRequestedAgain() {
        val first=roles(listOf(texts.first())).single()
        val existing=VoiceRoleRouting.Result(listOf(first,null,null),listOf("ollama",null,null))
        val result=VoiceRoleRouting.resolve(texts,listOf("ollama","local"),"",{ true },
            request = { _, input, _ -> assertFalse(texts.first() in input); roles(input) },existing=existing)
        assertEquals(first,result.plans.first())
        assertEquals(listOf("ollama","ollama","ollama"),result.providers)
        assertEquals(setOf(VoiceRole.AUTHOR,VoiceRole.MALE,VoiceRole.FEMALE),result.plans.flatMap { it.orEmpty() }.map { it.role }.toSet())
    }
    @Test fun unsupportedLocalRuntimeCannotLeaveTheQueueWaitingForever() {
        var failed = false
        val result = VoiceRoleRouting.resolve(texts,listOf("local"),"",{ true },
            request = { _, _, _ -> throw UnsatisfiedLinkError("Missing native runtime") },
            failed = { _, _ -> failed = true })
        assertTrue(failed)
        assertTrue(result.plans.all { it == null })
    }
    @Test fun deadlineStopsAdditionalBatchesWithoutDiscardingCompletedParagraphs() {
        var allowed = true
        var calls = 0
        val result = VoiceRoleRouting.resolve(listOf("Первый абзац.", "Второй ".repeat(700)),listOf("ollama"),"",{ allowed },
            request = { _, input, _ -> calls++; allowed = false; input.map { listOf(VoiceRoleText(it,VoiceRole.AUTHOR)) } })
        assertEquals(1,calls)
        assertNotNull(result.plans.first())
        assertNull(result.plans.last())
    }
    @Test fun offlineNeverCallsCloudAndMissingLocalNeverBlocksReading() {
        var requested = false
        val result = VoiceRoleRouting.resolve(texts, listOf("ollama", "local"), "", { false }, { _, _, _ -> requested = true; error("unexpected") })
        assertFalse(requested)
        assertTrue(result.plans.all { it == null })
    }
}
