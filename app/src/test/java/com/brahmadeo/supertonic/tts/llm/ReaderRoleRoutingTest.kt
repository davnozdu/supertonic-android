package com.brahmadeo.supertonic.tts.llm

import com.brahmadeo.supertonic.tts.books.ReplyIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/** Role identity must survive the book index and a reader that queues future paragraphs. */
class ReaderRoleRoutingTest {
    private data class Line(val text: String, val role: VoiceRole, val who: String?)
    private fun checkConversation(role: VoiceRole) {
        val rows = listOf(
            Line("Первый собеседник переспросил второго.", VoiceRole.AUTHOR, null),
            Line("— Ты уже закончил работу?", role, "first"),
            Line("— Ещё нет, подожди немного.", role, "second"),
            Line("— Я тоже хочу ответить!", role, "third"),
            Line("Второй отложил книгу на стол.", VoiceRole.AUTHOR, null),
            Line("— Сначала дослушайте мой рассказ.", role, "second"),
            Line("— Ты согласен со мной?", role, "first"),
            Line("— Да.", role, "second"),
            Line("Третий обратился к первому собеседнику.", VoiceRole.AUTHOR, null),
            Line("— А ты останешься здесь?", role, "third"),
            Line("— Да.", role, "first"))
        val builder = ReplyIndex.Companion.Builder()
        for (row in rows) builder.add(row.text, listOf(VoiceRoleText(row.text, row.role, row.who)))
        val (codes, data) = builder.export()
        val index = ReplyIndex.parse(codes, data)
        val context = ReaderRequestContext()
        // All future requests arrive before the first one is synthesised.
        rows.forEach { context.record(42, it.text) }
        for (row in rows) {
            val resolved = index.resolve(row.text, context.take(42, row.text)) { _, who -> who?.let { "voice-$it" } }!!
            assertEquals(row.text, resolved.joinToString("") { it.text })
            assertEquals(row.text, listOf(row.role), resolved.map { it.role }.distinct())
            assertEquals(row.text, listOf(row.who), resolved.map { it.character }.distinct())
            assertEquals(row.text, listOf(row.who?.let { "voice-$it" }), resolved.map { it.voice }.distinct())
        }
    }
    @Test fun threeMenKeepTheirIdentitiesThroughInterruptionsAndRepeatedShortAnswers() = checkConversation(VoiceRole.MALE)
    @Test fun threeWomenKeepTheirIdentitiesThroughInterruptionsAndRepeatedShortAnswers() = checkConversation(VoiceRole.FEMALE)
}
