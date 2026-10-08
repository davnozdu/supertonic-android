package com.brahmadeo.supertonic.tts.books

import com.brahmadeo.supertonic.tts.books.prepare.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookPreparationTest {
    private fun book(vararg paragraphs: String) = EpubBook("Тест", "", listOf(EpubBook.Section("s1", "Глава")),
        paragraphs.map { EpubBook.Paragraph(0, it) })
    private fun candidate(id: String, key: String, gender: String = "?", source: String = "verb", kind: String = "name") =
        NameCandidates.Candidate(key, kind, 0).apply {
            this.id = id; count = 10; forms[key.replaceFirstChar(Char::uppercaseChar)] = 10
            if (gender != "?") genders["$source:$gender"] = 3
        }
    private fun answer(chars: String, other: String = "[]") = JSONObject("""{"characters":[$chars],"other":$other}""")
    private fun character(id: String, name: String, refs: String, gender: String = "m") =
        """{"id":"$id","name":"$name","gender":"$gender","candidates":[$refs]}"""
    private fun checks(vararg verdicts: Pair<String, String>) = JSONObject().put("checks", org.json.JSONArray(verdicts.map {
        JSONObject().put("candidate", it.first).put("verdict", it.second)
    }))

    @Test fun formsComeFromBookStatistics() {
        val f = NameCandidates(emptySet())
        f.statistics(book(*Array(4) { "Вошли Мышкин, Филипповна, Тоцкий, Ганя и Сергей." },
            "Видели Мышкина, Филипповны, Тоцкого, Ганю и Сергея."))
        assertEquals("мышкин", f.base("Мышкина")); assertEquals("филипповна", f.base("Филипповны"))
        assertEquals("тоцкий", f.base("Тоцкого")); assertEquals("ганя", f.base("Ганю"))
        assertEquals("сергей", f.base("Сергея")); assertEquals("несуществующего", f.base("Несуществующего"))
    }
    @Test fun masculineDiminutivesUseRemarkVerbs() {
        val e = NameCandidates(setOf("ганя", "коля")).extract(book("— Да, — сказал Ганя.", "— Нет, — ответил Ганя.",
            "— Хорошо, — сказал Коля.", "— Завтра, — ответил Коля."))
        for (key in listOf("ганя", "коля")) {
            val c = e.candidates.single { it.key == key }; assertEquals("m", c.gender); assertEquals(2, c.speaker)
        }
    }
    @Test fun unknownGenderIsNotGuessedFromAEnding() {
        val e = NameCandidates(setOf("ганя")).extract(book("Пришёл Ганя.", "Позвали Ганя."))
        assertEquals("?", e.candidates.single().gender)
    }
    @Test fun startWordsCommonWordsAndPossessivesAreFiltered() {
        val e = NameCandidates(setOf("зина")).extract(book(*Array(8) { "Вышла Зина." },
            "Фу, Ай, Ну! Зинина чашка на столе.", "это стол. Стол пуст. Стол закрыт."))
        assertEquals(listOf("зина"), e.candidates.map { it.key })
    }
    @Test fun duplicateAndUnknownCandidatesNeverMultiplyCharacters() {
        val cs = listOf(candidate("c1", "ганя"), candidate("c2", "коля"))
        val result = CastCheck.apply(cs, answer(character("g", "Ганя", "\"c1\",\"c99\"") + "," +
            character("k", "Коля", "\"c1\",\"c2\"")), checks("c2" to "same"))
        assertEquals(listOf("c1", "c2"), result.characters.flatMap { it.refs })
        assertEquals(2, result.problems.size)
    }
    @Test fun missingUnsureAndConflictingChecksCannotMerge() {
        val cs = listOf(candidate("c1", "ганя", "m"), candidate("c2", "гаврила", "m"))
        val a = answer(character("g", "Ганя", "\"c1\",\"c2\""))
        for (v in listOf(null, checks("c2" to "unsure"), checks("c2" to "different"), checks("c2" to "same", "c2" to "unsure"))) {
            val result = CastCheck.apply(cs, a, v)
            assertEquals(listOf("c1"), result.characters.single().refs); assertEquals(listOf("c2"), result.other)
        }
        assertTrue(CastCheck.apply(cs, a, checks("c2" to "same")).other.isEmpty())
    }
    @Test fun reliableGenderRejectsEvenConfirmedMerge() {
        val cs = listOf(candidate("c1", "ганя", "m"), candidate("c2", "варя", "f", "Patr"))
        val r = CastCheck.apply(cs, answer(character("g", "Ганя", "\"c1\",\"c2\"")), checks("c2" to "same"))
        assertEquals(listOf("c2"), r.other)
    }
    @Test fun bareTitleSharedByTwoPeopleIsOther() {
        val first = candidate("c1", "иволгин", "m").apply { titles["генерал"] = 5 }
        val second = candidate("c2", "епанчин", "m").apply { titles["генерал"] = 5 }
        val title = candidate("c3", "генерал", "m", "Title", "title")
        val r = CastCheck.apply(listOf(first, second, title), answer(character("i", "Иволгин", "\"c1\",\"c3\"") + "," +
            character("e", "Епанчин", "\"c2\"")), checks("c3" to "same"))
        assertEquals(listOf("c3"), r.other)
    }
    @Test fun inventedFullNameIsReplacedByBookWords() {
        val c = candidate("c1", "ганя")
        val r = CastCheck.apply(listOf(c), answer(character("g", "Гаврила (Ганя) Иванович", "\"c1\"")), null)
        assertEquals("Ганя", r.characters.single().name)
    }
    @Test fun malformedCloudReplyIsAnError() {
        for (raw in listOf("", "[]", "{}", "{\"characters\":[]} ", "{bad}")) assertTrue(runCatching { CastCheck.parse(raw) }.isFailure)
        assertTrue(runCatching { CastCheck.parse("{\"checks\":[]}", true) }.isSuccess)
    }
    @Test fun epubNavAnchorsNestedBlocksAndExport() {
        val file = File.createTempFile("book-test", ".epub")
        try {
            val files = mapOf(
                "META-INF/container.xml" to "<container><rootfile full-path='OPS/book.opf'/></container>",
                "OPS/book.opf" to "<package><metadata><dc:title xmlns:dc='x'>Тест</dc:title></metadata><manifest>" +
                    "<item id='nav' href='nav.xhtml' properties='nav'/><item id='body' href='body.xhtml'/></manifest><spine><itemref idref='body'/></spine></package>",
                "OPS/nav.xhtml" to "<html xmlns='http://www.w3.org/1999/xhtml'><nav epub:type='toc'><ol>" +
                    "<li><a href='body.xhtml#a'>Первая</a><ol><li><a href='body.xhtml#ignore'>Вложенная</a></li></ol></li>" +
                    "<li><a href='body.xhtml#b'>Вторая</a></li></ol></nav></html>",
                "OPS/body.xhtml" to "<html xmlns='http://www.w3.org/1999/xhtml'><head><title/></head><body><div id='a'>" +
                    "<p>Длинное предложение для проверки <em>отпечатков</em> первой главы.</p><li><p>Второй абзац внутри списка.</p></li></div>" +
                    "<h1 id='b'>Вторая глава</h1><p>Длинное предложение для проверки второй главы и её отпечатка.</p></body></html>"
            )
            ZipOutputStream(file.outputStream()).use { z -> files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
            val b = EpubBook.read(file)
            assertEquals(2, b.sections.size); assertEquals(4, b.paragraphs.size)
            assertEquals(listOf(0, 0, 1, 1), b.paragraphs.map { it.section })
            // A book without names can still have a valid prepared package assembled mechanically.
            val c = candidate("c1", "пример")
            val request = BookPreparationPlan.Request("book", "Тест", listOf("s1", "s2"), listOf(c), "")
            val plan = BookPreparationPlan("Тест", "", "f", "c", b.sections, false, listOf(request),
                b.paragraphs.groupBy { b.sections[it.section].id }.mapValues { it.value.flatMap { p -> BookFingerprint.sentences(p.text).map(BookFingerprint::hex) } })
            val result = CastCheck.apply(listOf(c), answer("", "[\"c1\"]"), null)
            val pkg = BookPackage.parse(plan.export(listOf(result)))
            assertEquals(2, pkg.sections.size); assertEquals(listOf("Пример"), pkg.casts.single().other)
        } finally { file.delete() }
    }
    @Test fun cancelledExtractionStopsBeforeCloud() {
        assertTrue(runCatching { NameCandidates(emptySet()) { throw java.util.concurrent.CancellationException() }.extract(book("Вышел Ганя.")) }.isFailure)
    }
}
