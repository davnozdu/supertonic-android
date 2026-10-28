package com.brahmadeo.supertonic.tts.books

import com.brahmadeo.supertonic.tts.ambience.AmbienceBook
import com.brahmadeo.supertonic.tts.books.prepare.EpubBook
import com.brahmadeo.supertonic.tts.books.prepare.SceneMarking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Звуки событий»: one clip per kind of action. The same door in different chapters is one sound, several actions of
 * one paragraph all sound, and a re-worded quote is not lost. */
class AmbienceEventsTest {
    private fun p(text: String) = EpubBook.Paragraph(0, text)

    private val paragraphs = listOf(
        p("Князь вошёл в гостиную и притворил за собой дверь."),
        p("Он разложил на столе бумаги и начал читать письмо."),
        p("Через минуту дверь хлопнула снова, и все вздрогнули."),
        p("— Что вы говорите о бумагах? — спросил Рогожин.")
    )

    /** Four moments, two kinds: the door and the papers, each described differently the second time. */
    private val answer = """{"scenes":[{"from":1,"place":"гостиная","sound":"quiet drawing room, ticking clock, faint wind","accents":["a clock striking","a chair scraping"],"acoustics":"large_room","mood":"тревожное ожидание","music":"slow strings, 60 bpm"}],
        "events":[
        {"at":1,"quote":"притворил за собой дверь","kind":"door_close","sound":"a door closing with a soft thud"},
        {"at":2,"quote":"разложил на столе бумаги","kind":"paper","sound":"dry paper rustling and sheets being shuffled"},
        {"at":3,"quote":"дверь хлопнула снова","kind":"door_close","sound":"a heavy door slamming shut in anger"},
        {"at":4,"quote":"о бумагах спросил","kind":"paper","sound":"paper rustling in someone's hands"}]}"""

    @Test fun oneClipPerKindOfAction() {
        val result = SceneMarking.mark(paragraphs, { answer }, density = 3)
        assertEquals(4, result.marked)
        assertEquals(0, result.failed)
        assertEquals(4, result.events)
        assertEquals(2, result.kinds)
        assertEquals(0, result.dropped)
        // Two clips for four moments; each keeps the description of its first occurrence.
        assertEquals(listOf("a door closing with a soft thud", "dry paper rustling and sheets being shuffled"),
            result.index.events.events.map { it.sound })
    }

    @Test fun everyMomentIsFoundInItsOwnParagraph() {
        val index = SceneMarking.mark(paragraphs, { answer }, density = 3).index.events
        val before = ArrayList<String>()
        val found = ArrayList<Pair<String, Int>>()
        for (par in paragraphs) {
            for (hit in index.hitAll(par.text, before)) found += hit.event.id to hit.word
            before += ReplyIndex.words(par.text)
            while (before.size > ReplyIndex.WINDOW - 1) before.removeAt(0)
        }
        assertEquals(4, found.size)
        // The door of the first paragraph and the door of the third are the same clip.
        assertEquals(2, found.count { it.first == "e1" })
        assertEquals(2, found.count { it.first == "e2" })
    }

    @Test fun severalActionsOfOneParagraphAllSound() {
        val events = listOf(EventIndex.Event("e1", "a single gunshot"), EventIndex.Event("e2", "glass shattering"))
        val builder = EventIndex.Companion.Builder(events)
        val text = "В коридоре раздался выстрел, и стекло со звоном разбилось."
        builder.add(text, listOf("раздался выстрел" to 0, "стекло со звоном разбилось" to 1))
        val index = builder.build()
        val hits = index.hitAll(text, emptyList())
        assertEquals(listOf("e1", "e2"), hits.map { it.event.id })
        assertTrue("событие второго действия звучит позже первого", hits[0].word < hits[1].word)
        assertEquals(listOf("e1"), index.hitAll(text, emptyList(), 1).map { it.event.id })
        assertEquals("e1", index.hit(text, emptyList())!!.event.id)
    }

    @Test fun rewordedQuoteStillFindsTheAction() {
        val words = ReplyIndex.words("Он с треском захлопнул дверь.")
        assertEquals(1, EventIndex.anchorStart(words, ReplyIndex.words("с треском захлопнул дверь")))
        // The model changed the beginning: the rest of the quote still places the event.
        assertEquals(2, EventIndex.anchorStart(words, ReplyIndex.words("с громким треском захлопнул дверь")))
        assertNull(EventIndex.anchorStart(words, ReplyIndex.words("зелёный слоник прыгал")))
    }

    @Test fun anActionWithoutItsQuoteIsRefusedAndCounted() {
        val bad = """{"scenes":[{"from":1,"place":"гостиная","sound":"quiet drawing room, ticking clock"}],
            "events":[{"at":1,"quote":"зелёный слоник прыгал","kind":"door_close","sound":"a door closing with a soft thud"}]}"""
        val result = SceneMarking.mark(paragraphs, { bad })
        assertEquals(0, result.events)
        assertEquals(1, result.dropped)
        assertEquals(0, result.index.events.events.size)
    }

    @Test fun aKnownKindNeedsNoDescriptionOfItsOwn() {
        val ans = """{"scenes":[{"from":1,"place":"гостиная","sound":"quiet drawing room, ticking clock"}],
            "events":[{"at":1,"quote":"притворил за собой дверь","kind":"door_close","sound":""}]}"""
        val result = SceneMarking.mark(paragraphs, { ans })
        assertEquals(1, result.events)
        assertEquals(1, result.kinds)
        assertEquals("a door closing with a soft thud", result.index.events.events.single().sound)
    }

    @Test fun everyKindHasAnEnglishDescription() {
        for (kind in EventKinds.ALL) {
            assertTrue("${kind.key}: ${kind.sound}", kind.sound.all { it in ' '..'~' })
            assertTrue("${kind.key}: ${kind.sound}", kind.sound.count { it in 'a'..'z' || it in 'A'..'Z' } >= 8)
        }
        assertEquals(EventKinds.ALL.size, EventKinds.ALL.map { it.key }.toSet().size)
    }

    @Test fun russianDescriptionsNeverReachTheGenerator() {
        val ans = """{"scenes":[{"from":1,"place":"гостиная","sound":"тихая гостиная с часами"}],
            "events":[{"at":1,"quote":"притворил за собой дверь","kind":"door_close","sound":"тихо закрылась дверь"}]}"""
        val result = SceneMarking.mark(paragraphs, { ans })
        assertEquals(0, result.index.locations.size) // no English description — the place plays the music
        assertEquals(1, result.events)
        assertEquals("a door closing with a soft thud", result.index.events.events.single().sound)
    }

    @Test fun everyAccentGetsItsOwnVariant() {
        val location = SceneIndex.Location("l1", "купе", "train interior, wheel clacking", 12,
            listOf("a whistle blows twice", "brakes squeal and steam hisses", "a conductor's steps", "a door sliding"))
        assertEquals("train interior, wheel clacking", AmbienceBook.prompt(location, 0))
        assertEquals("train interior, wheel clacking, a whistle blows twice", AmbienceBook.prompt(location, 1))
        assertEquals("train interior, wheel clacking, a door sliding", AmbienceBook.prompt(location, 4))
        assertEquals("train interior, wheel clacking, a whistle blows twice", AmbienceBook.prompt(location, 5))
        assertEquals("train interior, wheel clacking", AmbienceBook.prompt(location.copy(accents = emptyList()), 3))
    }
}
