package com.brahmadeo.supertonic.tts.books

import com.brahmadeo.supertonic.tts.books.prepare.EpubBook
import com.brahmadeo.supertonic.tts.books.prepare.ReplyMarking
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyMarkingTest {
    @Test fun recalledSpeechMissedInBatchIsReviewedWithContext() {
        val text = "Берегись его, сказала Анна. Он опасен."
        val input = listOf(EpubBook.Paragraph(0, "Анна встретила меня у двери."), EpubBook.Paragraph(0, text))
        val batches = ReplyMarking.batches(listOf(EpubBook.Section("s1", "I")), input, pkg)
        var calls = 0
        val result = ReplyMarking.mark(input, batches, pkg, { batch ->
            calls++
            if (calls == 1) """{"paragraphs":[{"id":0,"segments":[{"start":0,"end":6,"role":"author","confidence":"clear"}]}]}"""
            else {
                assertEquals(listOf(1), batch.paragraphs)
                val prompt = org.json.JSONObject(batch.prompt)
                assertEquals(true, prompt.getString("context_before").contains("Анна встретила"))
                """{"paragraphs":[{"id":0,"segments":[{"start":0,"end":2,"role":"female","confidence":"clear"},{"start":2,"end":4,"role":"author","confidence":"clear"},{"start":4,"end":6,"role":"female","confidence":"clear"}]}]}"""
            }
        })
        assertEquals(2, calls)
        val index = result.replies.let { o -> ReplyIndex.parse((0 until o.getJSONArray("codes").length()).map { o.getJSONArray("codes").getString(it) }, o.getString("index")) }
        val parts = index.resolve(text, ReplyIndex.words(input[0].text)) { _, _ -> null }!!
        assertEquals(listOf(com.brahmadeo.supertonic.tts.llm.VoiceRole.FEMALE, com.brahmadeo.supertonic.tts.llm.VoiceRole.AUTHOR, com.brahmadeo.supertonic.tts.llm.VoiceRole.FEMALE), parts.map { it.role })
        assertEquals(text, parts.joinToString("") { it.text })
    }

    @Test fun indirectSpeechRemainsAuthorWhenReviewIsAuthorOrBroken() {
        val text = "Она сказала, что завтра будет дождь."
        val input = listOf(EpubBook.Paragraph(0, text))
        val batches = ReplyMarking.batches(listOf(EpubBook.Section("s1", "I")), input, pkg)
        val author = """{"paragraphs":[{"id":0,"segments":[{"start":0,"end":6,"role":"author","confidence":"clear"}]}]}"""
        for (review in listOf(author, "broken")) {
            var calls = 0
            val result = ReplyMarking.mark(input, batches, pkg, { if (++calls == 1) author else review })
            assertEquals(2, calls)
            assertEquals(listOf("a"), result.replies.getJSONArray("codes").let { c -> (0 until c.length()).map { c.getString(it) } })
        }
    }

    @Test fun recalledSpeechWithoutDashesReachesTheLlmAndItsFemaleVoiceSurvives() {
        val text = "Будь осторожен, сказала Анна. Здесь опасно."
        val input = listOf(EpubBook.Paragraph(0, text))
        val batches = ReplyMarking.batches(listOf(EpubBook.Section("s1", "I")), input, pkg)
        assertEquals(listOf(listOf(0)), batches.map { it.paragraphs })
        val answer = """{"paragraphs":[{"id":0,"segments":[{"start":0,"end":2,"role":"female","confidence":"clear"},{"start":2,"end":4,"role":"author","confidence":"clear"},{"start":4,"end":6,"role":"female","confidence":"clear"}]}]}"""
        val result = ReplyMarking.mark(input, batches, pkg, { answer })
        assertEquals(1, result.marked)
        val codes = result.replies.getJSONArray("codes")
        assertEquals(true, (0 until codes.length()).any { codes.getString(it) == "f" })
    }

    @Test fun simpleNarrationStillNeedsNoCloudRoleRequest() {
        assertEquals(false, ReplyMarking.mayHaveSpeech("Вечерело. На улице шёл дождь."))
        assertEquals(true, ReplyMarking.mayHaveSpeech("Он опасен, сказала она. Не ходи туда."))
    }
    private val pkg = BookPackage("Т", "", "f", "c", "book", listOf(BookPackage.Section("s1", "I", 0)),
        listOf(BookPackage.Cast(listOf("s1"), listOf(BookPackage.Character("rogozhin", "Рогожин", "m", 1, 1, listOf("Рогожин"), null)), emptyList())),
        emptyMap())
    private val paragraphs = listOf(
        EpubBook.Paragraph(0, "Черноволосый сосед всё это разглядел от нечего делать."),
        EpubBook.Paragraph(0, "— Зябко? — спросил Рогожин."))

    @Test fun onlyDialogueIsAskedAndEveryWordGetsItsVoice() {
        val batches = ReplyMarking.batches(listOf(EpubBook.Section("s1", "I")), paragraphs, pkg)
        assertEquals(listOf(listOf(1)), batches.map { it.paragraphs })
        // The model's answer: «— Зябко?» Рогожин, the remark the author.
        val answer = """{"paragraphs":[{"id":0,"segments":[{"start":0,"end":2,"role":"male","confidence":"clear","speaker":"rogozhin"},{"start":2,"end":5,"role":"author","confidence":"clear"}]}]}"""
        val result = ReplyMarking.mark(paragraphs, batches, pkg, { answer })
        assertEquals(1, result.marked); assertEquals(0, result.failed)
        val index = ReplyIndex.parse((0 until result.replies.getJSONArray("codes").length()).map { result.replies.getJSONArray("codes").getString(it) },
            result.replies.getString("index"))
        val before = ReplyIndex.words(paragraphs[0].text).takeLast(3)
        val parts = index.resolve("Зябко? — спросил Рогожин.", before) { _, _ -> null }!!
        assertEquals(listOf("M:rogozhin=Зябко? ", "A=— спросил Рогожин."), parts.map { "${it.role.name.first()}${it.character?.let { c -> ":$c" } ?: ""}=${it.text}" })
        // A broken answer marks nothing and does not stop the book.
        assertEquals(1, ReplyMarking.mark(paragraphs, batches, pkg, { "не JSON" }).failed)
    }
}
