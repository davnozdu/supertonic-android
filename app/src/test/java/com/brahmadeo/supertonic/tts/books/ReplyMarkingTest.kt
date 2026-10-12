package com.brahmadeo.supertonic.tts.books

import com.brahmadeo.supertonic.tts.books.prepare.EpubBook
import com.brahmadeo.supertonic.tts.books.prepare.ReplyMarking
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyMarkingTest {
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
