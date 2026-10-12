package com.brahmadeo.supertonic.tts.books

import com.brahmadeo.supertonic.tts.llm.VoiceRole
import com.brahmadeo.supertonic.tts.llm.VoiceRoleText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyIndexTest {
    private val paragraph = "— Очень, — ответил сосед с чрезвычайною готовностью, — и заметьте, это еще оттепель. Что ж, если бы мороз?"
    // The LLM's plan over the paragraph: the reply is Рогожин's, the remark the author's.
    private val plan = listOf(
        VoiceRoleText("— Очень, ", VoiceRole.MALE, "rogozhin"),
        VoiceRoleText("— ответил сосед с чрезвычайною готовностью, ", VoiceRole.AUTHOR),
        VoiceRoleText("— и заметьте, это еще оттепель. Что ж, если бы мороз?", VoiceRole.MALE, "rogozhin"))
    private fun show(parts: List<VoiceRoleText>?) = parts?.joinToString(" | ") { "${it.role.name.first()}${it.character?.let { c -> ":$c" } ?: ""}=${it.text.trim()}" }

    @Test fun entriesPerSentenceByWordNumber() {
        val e = ReplyIndex.entries(paragraph, plan, "Зябко?")
        assertEquals(listOf("0:m:rogozhin;1:a;6:m:rogozhin", "0:m:rogozhin"), e.map { it.second })
        // The first sentence has both keys (context + its own ≥ 24 letters); the short second one only the context key.
        assertEquals(listOf(2, 1), e.map { it.first.size })
    }

    @Test fun aReaderWithoutTheDashAndWithStressMarksGetsTheSameVoices() {
        val index = HashMap<Long, String>()
        ReplyIndex.entries(paragraph, plan, "Зябко?").forEach { (keys, code) -> keys.forEach { index[it] = code } }
        // Readest: the leading dash dropped, sentence by sentence.
        val r1 = ReplyIndex.resolve("Очень, — ответил сосед с чрезвычайною готовностью, — и заметьте, это еще оттепель.", "Зябко?", index::get) { _, _ -> null }
        assertEquals("M:rogozhin=Очень, | A=— ответил сосед с чрезвычайною готовностью, | M:rogozhin=— и заметьте, это еще оттепель.",
            show(r1.single().second))
        val r2 = ReplyIndex.resolve("Что ж, если бы мороз?", r1.single().first, index::get) { role, who -> if (who != null) "voice-$who" else null }
        assertEquals("voice-rogozhin", r2.single().second!!.single().voice)
        // Moon+: the whole paragraph with the LLM's stress marks.
        val moon = ReplyIndex.resolve("— О́чень, — отве́тил сосе́д с чрезвыча́йною гото́вностью, — и заме́тьте, э́то еще́ о́ттепель. Что ж, е́сли бы моро́з?",
            "Зя́бко?", index::get) { _, _ -> null }
        assertEquals(2, moon.count { it.second != null })
        // Without the previous paragraph: the long sentence by its own key, the short one by the first as context.
        val cold = ReplyIndex.resolve(paragraph, null, index::get) { _, _ -> null }
        assertEquals(listOf(true, true), cold.map { it.second != null })
        // A short sentence alone, with no context, is not guessed.
        assertNull(ReplyIndex.resolve("Что ж, если бы мороз?", null, index::get) { _, _ -> null }.single().second)
    }

    @Test fun codesAreStrict() {
        assertNull(ReplyIndex.decode("1:m"))
        assertNull(ReplyIndex.decode("0:x"))
        assertNull(ReplyIndex.decode("0:m;0:a"))
        assertEquals(listOf(ReplyIndex.Part(0, VoiceRole.FEMALE, "nastasya"), ReplyIndex.Part(3, VoiceRole.AUTHOR, null)),
            ReplyIndex.decode("0:f:nastasya;3:a"))
        assertEquals(true, ReplyIndex.isDialogue("— Да."))
        assertEquals(false, ReplyIndex.isDialogue("Он вышел."))
    }
}
