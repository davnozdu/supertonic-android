package com.brahmadeo.supertonic.tts.books

import com.brahmadeo.supertonic.tts.llm.VoiceRole
import com.brahmadeo.supertonic.tts.llm.VoiceRoleText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyIndexTest {
    private val narration = "Черноволосый сосед в крытом тулупе всё это разглядел, частию от нечего делать, и наконец спросил."
    private val ask = "— Зябко?"
    private val paragraph = "— Очень, — ответил сосед с чрезвычайною готовностью, — и заметьте, это еще оттепель. Что ж, если бы мороз?"
    private val plan = listOf(
        VoiceRoleText("— Очень, ", VoiceRole.MALE, "myshkin"),
        VoiceRoleText("— ответил сосед с чрезвычайною готовностью, ", VoiceRole.AUTHOR),
        VoiceRoleText("— и заметьте, это еще оттепель. Что ж, если бы мороз?", VoiceRole.MALE, "myshkin"))
    private fun show(parts: List<VoiceRoleText>?) = parts?.joinToString(" | ") { "${it.role.name.first()}${it.character?.let { c -> ":$c" } ?: ""}=${it.text.trim()}" }

    private fun index(): ReplyIndex {
        val b = ReplyIndex.Companion.Builder()
        b.add(narration, null)
        b.add(ask, listOf(VoiceRoleText(ask, VoiceRole.MALE, "rogozhin")))
        b.add(paragraph, plan)
        val (codes, data) = b.export()
        return ReplyIndex.parse(codes, data)
    }

    @Test fun anyCuttingOfTheTextGetsTheSameVoices() {
        val idx = index()
        val before = ReplyIndex.words(narration + " " + ask).takeLast(3)
        // Moon+: the whole paragraph, with stress marks from the LLM.
        assertEquals("M:myshkin=— О́чень, | A=— отве́тил сосе́д с чрезвыча́йною гото́вностью, | M:myshkin=— и заме́тьте, э́то еще́ о́ттепель. Что ж, е́сли бы моро́з?",
            show(idx.resolve("— О́чень, — отве́тил сосе́д с чрезвыча́йною гото́вностью, — и заме́тьте, э́то еще́ о́ттепель. Что ж, е́сли бы моро́з?", before) { _, _ -> null }))
        // Readest: no leading dash, cut at inline tags — a piece may start with a comma.
        val p1 = "Очень"
        assertEquals("M:myshkin=Очень", show(idx.resolve(p1, before) { _, _ -> null }))
        val b2 = (before + ReplyIndex.words(p1)).takeLast(3)
        assertEquals("A=, — ответил сосед с чрезвычайною готовностью, | M:myshkin=— и заметьте, это еще оттепель.",
            show(idx.resolve(", — ответил сосед с чрезвычайною готовностью, — и заметьте, это еще оттепель.", b2) { _, _ -> null }))
        // The voice callback gives the character's voice.
        assertEquals("voice-rogozhin", idx.resolve("Зябко?", ReplyIndex.words(narration).takeLast(3)) { _, who -> who?.let { "voice-$it" } }!!.single().voice)
    }

    @Test fun narrationAndUnknownTextAreNotGuessed() {
        val idx = index()
        assertNull(idx.resolve(narration, emptyList()) { _, _ -> null })
        assertNull(idx.resolve("Совсем другой текст, которого нет в книге.", emptyList()) { _, _ -> null })
        assertEquals(true, ReplyIndex.isDialogue("— Да."))
        assertEquals(false, ReplyIndex.isDialogue("Он вышел."))
    }

    @Test fun storedFormIsStrict() {
        val (codes, data) = ReplyIndex.Companion.Builder().apply { add(ask + " " + paragraph, null) }.export()
        assertEquals(0, ReplyIndex.parse(codes, data).size)
        assertNull(ReplyIndex.voice("x"))
        assertEquals(ReplyIndex.Voice(VoiceRole.AUTHOR, null), ReplyIndex.voice("a:someone"))
        val bad = runCatching { ReplyIndex.parse(listOf("m"), java.util.Base64.getEncoder().encodeToString(ByteArray(10).also { it[9] = 5 })) }
        assertEquals(true, bad.isFailure)
    }

    /** Реплика в начале абзаца: четырёх слов для окна ещё нет — раньше такие слова выпадали и читались автором. */
    @Test fun replyAtParagraphStartIsFound() {
        val text = "— Ты пришёл, — сказала Анна Павловна."
        val builder = ReplyIndex.Companion.Builder()
        builder.add(text, listOf(
            VoiceRoleText("— Ты пришёл, ", VoiceRole.FEMALE, "anna"),
            VoiceRoleText("— сказала Анна Павловна.", VoiceRole.AUTHOR)))
        val (codes, index) = builder.export()
        val resolved = ReplyIndex.parse(codes, index)!!.resolve(text, emptyList()) { role, _ -> role.name }
        assertEquals(VoiceRole.FEMALE, resolved!!.first().role)
    }

    /** Повествование без реплик остаётся за автором. */
    @Test fun narrationStaysAuthor() {
        val text = "Анна Павловна вошла и села у окна."
        val builder = ReplyIndex.Companion.Builder()
        builder.add(text, listOf(VoiceRoleText(text, VoiceRole.AUTHOR)))
        val (codes, index) = builder.export()
        val resolved = ReplyIndex.parse(codes, index)!!.resolve(text, emptyList()) { role, _ -> role.name }
        assertEquals(VoiceRole.AUTHOR, resolved!!.single().role)
    }
}
