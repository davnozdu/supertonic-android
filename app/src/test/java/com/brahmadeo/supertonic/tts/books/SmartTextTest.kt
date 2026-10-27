package com.brahmadeo.supertonic.tts.books

import com.brahmadeo.supertonic.tts.books.smart.SmartPrompts
import com.brahmadeo.supertonic.tts.books.smart.SmartText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTextTest {
    private val book = SmartText.of("Повесть", "Автор", listOf("Глава первая", "Глава вторая", "Глава третья"), listOf(
        0 to "Князь Лев Николаевич приехал в Петербург ранним утром в ноябре.",
        0 to "В вагоне он познакомился с Рогожиным, который возвращался домой за наследством.",
        1 to "Генерал Епанчин принял князя в своём кабинете без особенной охоты.",
        1 to "Князь Лев Николаевич приехал в Петербург ранним утром в ноябре.", // the same sentence twice in the book
        1 to "Настасья Филипповна прислала свой портрет генералу в подарок к именинам.",
        2 to "Вечером у Настасьи Филипповны собралось много самых разных гостей."))

    @Test fun sectionsAndPlaces() {
        assertEquals(6, book.paragraphs)
        assertEquals(listOf(0, 0, 1, 1, 1, 2), (0 until 6).map(book::sectionOf))
        assertEquals(0, book.sectionOf(-5)); assertEquals(2, book.sectionOf(99))
        assertEquals("Генерал Епанчин принял князя в своём кабинете без особенной охоты.", book.sectionText(1, 2))
        assertEquals(3, book.sectionText(1).split('\n').size)
        assertEquals("", book.sectionText(1, 1)) // before the section begins
    }

    @Test fun fingerprintsPointAtParagraphs() {
        val prints = book.fingerprints()
        val first = BookFingerprint.sentences("Князь Лев Николаевич приехал в Петербург ранним утром в ноябре.").single()
        assertEquals(-1, prints[first]) // met in two paragraphs: points nowhere
        val portrait = BookFingerprint.sentences("Настасья Филипповна прислала свой портрет генералу в подарок к именинам.").single()
        assertEquals(4, prints[portrait])
    }

    @Test fun jsonRoundTripKeepsSummaries() {
        val text = book.copy(summaries = mapOf(1 to "Князь у генерала."))
        val back = SmartText.parse(org.json.JSONObject(text.toJson().toString()))
        assertEquals(text, back)
        assertEquals(text.layout, back.layout)
    }

    /** The same key as BookPreparationPlan's content_sha256: letters of every paragraph, one per line. */
    @Test fun contentKeyIgnoresMarkup() {
        assertEquals(SmartText.contentKey(listOf("Ёлка́, — сказал он.", "Второй")), SmartText.contentKey(listOf("елка сказал он", "второй")))
        assertTrue(SmartText.contentKey(listOf("а", "б")) != SmartText.contentKey(listOf("аб")))
    }

    @Test fun longSectionsAreCutBetweenParagraphs() {
        val text = (1..10).joinToString("\n") { "абзац $it " + "слово ".repeat(30) }
        val parts = SmartPrompts.parts(text, 500)
        assertTrue(parts.all { it.length <= 500 })
        assertEquals(text, parts.joinToString("\n"))
        assertEquals(listOf(text), SmartPrompts.parts(text, text.length))
        assertTrue(SmartPrompts.tail(text, 300).length <= 301)
    }

    @Test fun answerAndSpeech() {
        assertEquals("Князь прие́хал.", SmartPrompts.answer("```json\n{\"summary\": \" Князь прие́хал. \"}\n```"))
        assertEquals("просто текст", SmartPrompts.answer("  просто текст \n")); assertNull(SmartPrompts.answer("{\"x\": 1}"))
        assertEquals("Князь приехал.", SmartPrompts.display("Князь прие́хал."))
        val chunks = SmartPrompts.speechChunks("Первый абзац.\n\n" + "Длинное предложение номер раз. ".repeat(60), 300)
        assertEquals("Первый абзац.", chunks.first())
        assertTrue(chunks.all { it.length <= 300 })
    }

    /** Several sections per request; a section the model left out is asked alone; a tiny one costs nothing. */
    @Test fun summariesArePacked() {
        val long = "Слово ".repeat(400)
        val text = SmartText.of("Т", "А", listOf("Титул", "I", "II", "III"),
            listOf(0 to "Идиот", 1 to long + "один", 2 to long + "два", 3 to long + "три"))
        val asked = mutableListOf<Boolean>()
        val made = SmartPrompts.summarize(text, listOf(0, 1, 2, 3), emptyMap(), { prompt, _, multi ->
            asked += multi
            if (multi) "{\"summaries\": [{\"id\": 1, \"summary\": \"первая\"}, {\"id\": 3, \"summary\": \"третья\"}, {\"id\": 9, \"summary\": \"чужая\"}]}"
            else { assertTrue(prompt.contains("два")); "вторая" }
        }, { _, _ -> }, { _, _ -> })
        assertEquals(listOf(true, false), asked)
        assertEquals(mapOf(0 to "Идиот", 1 to "первая", 2 to "вторая", 3 to "третья"), made)
    }
}
