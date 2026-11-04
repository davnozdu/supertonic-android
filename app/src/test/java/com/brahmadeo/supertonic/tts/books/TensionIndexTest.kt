package com.brahmadeo.supertonic.tts.books

import com.brahmadeo.supertonic.tts.ambience.AmbienceBook
import com.brahmadeo.supertonic.tts.books.prepare.EpubBook
import com.brahmadeo.supertonic.tts.books.prepare.SceneMarking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Нагнетание напряжения»: уровни из ответа модели доходят до индекса, находятся по тексту читалки, переживают
 * сохранение, а без уровней (старая разметка) индекс пуст и ничего не меняет. */
class TensionIndexTest {
    private fun p(text: String) = EpubBook.Paragraph(0, text)

    private val paragraphs = listOf(
        p("Утро было тихим, мальчик пил чай на кухне и смотрел в окно."),
        p("Бабушка позвала его из комнаты странным чужим голосом, и ему стало не по себе."),
        p("Он медленно открыл дверь и увидел, что кровать пуста, а окно распахнуто настежь."),
        p("Что-то холодное схватило его за руку, и он закричал изо всех сил.")
    )

    private val answer = """{"scenes":[{"from":1,"place":"дом, утро","sound":"quiet house in the morning, kettle, birds outside","accents":[],"acoustics":"room","mood":"тревога","music":"low strings, 60 bpm"}],
        "events":[],"tension":[{"from":1,"level":0},{"from":2,"level":1},{"from":3,"level":2},{"from":4,"level":3}]}"""

    @Test fun levelsFollowTheText() {
        val index = SceneMarking.mark(paragraphs, { answer }).index.tension
        assertTrue(!index.isEmpty)
        val before = ArrayList<String>()
        val found = paragraphs.map { para -> index.find(para.text, before).also { before += ReplyIndex.words(para.text) } }
        assertEquals(listOf(0, 1, 2, 3), found)
    }

    @Test fun survivesSaving() {
        val scenes = SceneMarking.mark(paragraphs, { answer }).index
        val again = SceneIndex.parse(scenes.toJson()).tension
        assertEquals(3, again.find(paragraphs[3].text, ReplyIndex.words(paragraphs[2].text)))
    }

    @Test fun oldMarkupHasNoLevels() {
        val old = answer.substringBefore(",\"tension\"") + "}"
        val index = SceneMarking.mark(paragraphs, { old }).index.tension
        assertTrue(index.isEmpty)
        assertNull(index.find(paragraphs[0].text, emptyList()))
    }

    @Test fun threeStrengthsOfOneMood() {
        val calm = AmbienceBook.intensity("low strings", 0)
        val tense = AmbienceBook.intensity("low strings", 2)
        assertEquals("low strings", AmbienceBook.intensity("low strings", 1))
        assertTrue(calm.startsWith("low strings,") && tense.startsWith("low strings,") && calm != tense)
    }
}
