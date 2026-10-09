package com.brahmadeo.supertonic.tts.books

import com.brahmadeo.supertonic.tts.books.prepare.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookPreparationTest {
    /** A tiny dictionary in the format of assets/book_morph_ru.tsv.gz. */
    private val morph = BookMorph.load(sequenceOf(
        "n\tганя\tганя:nomn:f", "n\tгани\tганя:gent:f", "n\tлев\tлев:nomn:m", "n\tльва\tлев:gent:m|лев:accs:m",
        "n\tаглая\tаглая:nomn:f", "n\tаглаю\tаглая:accs:f", "n\tколя\tколя:nomn:m", "n\tнастасья\tнастасья:nomn:f",
        "n\tиван\tиван:nomn:m", "n\tивана\tиван:gent:m|иван:accs:m",
        "t\tкнязь\tкнязь:nomn:m:sing", "t\tкнязя\tкнязь:gent:m:sing|князь:accs:m:sing", "t\tгенерал\tгенерал:nomn:m:sing",
        "t\tгенерала\tгенерал:gent:m:sing|генерал:accs:m:sing", "t\tгенералы\tгенерал:nomn:m:plur",
        "p\tлакей\tm", "p\tстаруха\tf", "f\tну\tPRCL", "f\tон\tNPRO", "f\tему\tNPRO", "a\tтихо\tADVB", "a\tнаконец\tADVB",
        "g\tмосква\t", "g\tмоскве\t"))
    private fun book(vararg paragraphs: String, sections: Int = 1) = EpubBook("Тест", "",
        (1..sections).map { EpubBook.Section("s$it", "Глава $it") },
        paragraphs.mapIndexed { i, p -> EpubBook.Paragraph(i * sections / paragraphs.size, p) })
    private fun extract(vararg paragraphs: String) = NameCandidates(emptySet(), morph).run(BookStructure.normalized(book(*paragraphs)), false)

    // ------------------------------------------------------------ text and structure

    @Test fun normalizationIsMechanical() {
        assertEquals("— Да, — сказал Рогожин… «Вот»", BookStructure.normalize("-- Да, - сказал Pогожин[1]... \"Вот\""))
        assertEquals("— Нет.", BookStructure.normalize("­– Нет¹."))
        assertEquals("Москва", BookStructure.normalize("Москва"))
    }
    @Test fun notesAreEmptyForAnalysisButKeptForFingerprints() {
        val b = EpubBook("Т", "", listOf(EpubBook.Section("s1", "Глава")), listOf(EpubBook.Paragraph(0, "Рогожин¹ вошёл."),
            EpubBook.Paragraph(0, "¹ Сноска про Иванова.")), notes = setOf(1), analysis = mapOf(0 to "Рогожин вошёл."))
        val n = BookStructure.normalized(b)
        assertEquals(listOf("Рогожин вошёл.", ""), n.paragraphs.map { it.text })
        assertEquals(2, n.paragraphs.size)
    }
    @Test fun tinySectionsJoinTheNextOne() {
        val b = EpubBook("Т", "", (1..3).map { EpubBook.Section("s$it", "Ч$it") }, listOf(EpubBook.Paragraph(0, "ЧАСТЬ ПЕРВАЯ")) +
            (1..3).map { EpubBook.Paragraph(1, "Текст $it") } + (1..3).map { EpubBook.Paragraph(2, "Ещё $it") })
        val m = BookStructure.mergeTiny(b)
        assertEquals(listOf("Ч2", "Ч3"), m.sections.map { it.title }); assertEquals(listOf(0, 0, 0, 0, 1, 1, 1), m.paragraphs.map { it.section })
    }
    @Test fun collectionByConcentrationOfHeroes() {
        fun c(key: String, vararg counts: Int) = NameCandidates.Candidate(key, "name", 0).apply {
            count = counts.sum(); counts.forEachIndexed { i, n -> if (n > 0) sections[i] = n }
        }
        val sections = (1..4).map { EpubBook.Section("s$it", "Рассказ $it") }
        val b = EpubBook("Т", "", sections, emptyList())
        assertTrue(BookStructure.collection(b, listOf(c("а", 30, 0, 0, 0), c("б", 0, 20, 1, 0), c("в", 0, 0, 25, 0))))
        assertFalse(BookStructure.collection(b, listOf(c("а", 10, 10, 10, 10), c("б", 5, 6, 7, 8))))
        // Repeated titles («Тед», «Оливия», «Тед»): chapters of a novel with several narrators.
        assertFalse(BookStructure.collection(b.copy(sections = sections.map { it.copy(title = "Тед") }), listOf(c("а", 30, 0, 0, 0))))
    }

    @Test fun epubNestedContentsNotesAndNoterefs() {
        val file = File.createTempFile("book-test", ".epub")
        try {
            val files = mapOf(
                "META-INF/container.xml" to "<container><rootfile full-path='OPS/book.opf'/></container>",
                "OPS/book.opf" to "<package><metadata><dc:title xmlns:dc='x'>Тест</dc:title></metadata><manifest>" +
                    "<item id='nav' href='nav.xhtml' properties='nav'/><item id='body' href='body.xhtml'/><item id='notes' href='notes.xhtml'/></manifest>" +
                    "<spine><itemref idref='body'/><itemref idref='notes' linear='no'/></spine></package>",
                "OPS/nav.xhtml" to "<html xmlns='http://www.w3.org/1999/xhtml'><nav epub:type='toc'><ol>" +
                    "<li><a href='body.xhtml#a'>Часть первая</a><ol><li><a href='body.xhtml#a1'>I</a></li><li><a href='body.xhtml#a2'>II</a></li></ol></li>" +
                    "<li><a href='body.xhtml#b'>Часть вторая</a></li></ol></nav></html>",
                "OPS/body.xhtml" to "<html xmlns='http://www.w3.org/1999/xhtml'><head><title/></head><body><div id='a'><h1>Часть первая</h1>" +
                    "<p id='a1'>Рогожин<sup><a href='notes.xhtml#n1'>1</a></sup> вошёл в комнату <em>первой</em> главы.</p><p id='a2'>Второй абзац второй главы.</p>" +
                    "<aside epub:type='footnote'><p>Сноска внутри главы.</p></aside></div>" +
                    "<h1 id='b'>Часть вторая</h1><p>Длинное предложение для проверки второй части и её отпечатка.</p></body></html>",
                "OPS/notes.xhtml" to "<html xmlns='http://www.w3.org/1999/xhtml'><body><p id='n1'>Примечание в конце книги.</p></body></html>",
            )
            ZipOutputStream(file.outputStream()).use { z -> files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() } }
            val top = EpubBook.read(file)
            assertEquals(listOf("Часть первая", "Часть вторая"), top.sections.map { it.title })
            val chapters = EpubBook.read(file, depth = EpubBook.ALL_LEVELS)
            assertEquals(listOf("Часть первая", "Часть первая I", "Часть первая II", "Часть вторая"), chapters.sections.map { it.title })
            assertEquals(top.paragraphs.map { it.text }, chapters.paragraphs.map { it.text })
            val i = top.paragraphs.indexOfFirst { it.text.startsWith("Рогожин") }
            assertEquals("Рогожин 1 вошёл в комнату первой главы.", top.paragraphs[i].text)
            assertEquals("Рогожин вошёл в комнату первой главы.", top.analysis[i])
            val notes = top.notes.map { top.paragraphs[it].text }.toSet()
            assertEquals(setOf("Сноска внутри главы.", "Примечание в конце книги."), notes)
        } finally { file.delete() }
    }
    @Test fun fb2NestedSectionsAsChapters() {
        val file = File.createTempFile("fb2-test", ".fb2")
        try {
            file.writeText("""<?xml version="1.0" encoding="UTF-8"?><FictionBook><description><title-info><book-title>Книга</book-title></title-info></description>
                <body><section><title><p>Часть</p></title><section><title><p>I</p></title><p>Первая глава<a type="note" l:href="#n1">[1]</a> текст.</p></section>
                <section><title><p>II</p></title><p>Вторая глава текст.</p></section></section></body></FictionBook>""")
            assertEquals(1, BookInput.read(file).sections.size)
            val b = BookInput.read(file, depth = EpubBook.ALL_LEVELS)
            assertEquals(listOf("Часть", "Часть I", "Часть II"), b.sections.map { it.title })
            val i = b.paragraphs.indexOfFirst { it.text.startsWith("Первая") }
            assertEquals("Первая глава текст.", b.analysis[i])
        } finally { file.delete() }
    }

    // ------------------------------------------------------------ candidates

    @Test fun remarksPastPresentParentheticalAndDescriptor() {
        val found = extract("— Да, — сказал Ганя.", "— Нет, — ответил, наконец, Ганя.", "— Хорошо, — говорит Коля.",
            "— Завтра, — сказал Коля.", "— Кто там? — спросил лакей.", "— Кто там? — спросил лакей.", "Вошли Ганя и Коля.")
        val ganya = found.single { it.key == "ганя" }
        assertEquals(2, ganya.speaker); assertEquals("m", ganya.gender) // the verb beats the dictionary («Ганя» f)
        assertEquals(2, found.single { it.key == "коля" }.speaker)
        val lackey = found.single { it.key == "лакей" }
        assertTrue(lackey.descriptor); assertEquals(2, lackey.speaker); assertEquals("title", lackey.kind)
    }
    @Test fun titlesInAllCasesAndBeforeNames() {
        val found = extract("Князь вошёл.", "Позвали князя.", "— Да, — сказал князь.", "Генерал Иволгин сел.", "Генералы ушли.", "Иволгин молчал.")
        val prince = found.single { it.key == "князь" }
        assertEquals(3, prince.count); assertEquals(1, prince.speaker); assertEquals("m", prince.gender)
        val ivolgin = found.single { it.key == "иволгин" }
        assertEquals(1, ivolgin.titles["генерал"]); assertNull(found.firstOrNull { it.key == "генерал" })
    }
    @Test fun namesAgreeTwoSurnamesAreTwoPeopleGeographyIsNotAName() {
        val found = extract("Пришёл Рогожин.", "— Здравствуй, — сказал Рогожин Лебедеву.", "Лебедев кивнул.", "Видели Льва в Москве.",
            "Лев засмеялся.", "Москва спала.", "Шли по Москве.")
        assertTrue(found.any { it.key == "рогожин" } && found.any { it.key == "лебедев" })
        assertTrue(found.none { " " in it.key && "рогожин" in it.key })
        assertEquals(2, found.single { it.key == "лев" }.count)
        assertTrue(found.none { it.key.startsWith("москв") })
    }
    @Test fun startWordsCommonWordsAndPossessivesAreFiltered() {
        val found = extract(*Array(8) { "Вышла Зина." }, "Ну, Он ушёл! Зинина чашка на столе.", "это стол. Стол пуст. Стол закрыт.")
        assertEquals(listOf("зина"), found.map { it.key })
    }
    @Test fun cancelledExtractionStopsBeforeCloud() {
        assertTrue(runCatching { NameCandidates(emptySet(), morph) { throw java.util.concurrent.CancellationException() }.extract(book("Вышел Ганя.")) }.isFailure)
    }

    // ------------------------------------------------------------ LLM answers

    private fun candidate(id: String, key: String, gender: String = "?", source: String = "verb", kind: String = "name", count: Int = 10) =
        NameCandidates.Candidate(key, kind, 0).apply {
            this.id = id; this.count = count; forms[key.replaceFirstChar(Char::uppercaseChar)] = count
            if (gender != "?") genders["$source:$gender"] = 3
            sectionCounts = mapOf("s1" to count)
        }
    private fun request(vararg cs: NameCandidates.Candidate, sections: List<String> = listOf("s1")) =
        BookPreparationPlan.Request("book", "Тест", sections, cs.toList(), "")
    private fun answer(chars: String, other: String = "[]") = JSONObject("""{"characters":[$chars],"other":$other}""")
    private fun character(id: String, name: String, refs: String, gender: String = "m") =
        """{"id":"$id","name":"$name","gender":"$gender","candidates":[$refs]}"""
    private fun checks(vararg verdicts: Triple<String, String, String>, character: String = "g") = JSONObject().put("checks", JSONArray(verdicts.map {
        JSONObject().put("character", character).put("anchor", it.first).put("candidate", it.second).put("verdict", it.third)
    }))

    @Test fun malformedRepliesAreRejected() {
        for (raw in listOf("", "[]", "{}", "{\"characters\":[]} ", "{bad}", "{\"characters\":[{\"id\":\"a\"}],\"other\":[]}")) assertNull(CastCheck.parse(raw, CastCheck.Kind.MAIN))
        assertNotNull(CastCheck.parse("{\"characters\":[{\"id\":\"a\",\"name\":\"А\",\"gender\":\"м\",\"candidates\":[\"c1\"]}],\"other\":[]}", CastCheck.Kind.MAIN))
        assertNotNull(CastCheck.parse("{\"checks\":[]}", CastCheck.Kind.VERIFY))
        assertNull(CastCheck.parse("{\"answers\":[{\"n\":\"x\",\"who\":\"a\"}]}", CastCheck.Kind.LABELS))
    }
    @Test fun majorityOfVotesDecidesMerges() {
        val r = request(candidate("c1", "ганя"), candidate("c2", "гаврила"), candidate("c3", "коля"))
        val a = answer(character("ganya", "Ганя", "\"c1\",\"c2\"") + "," + character("kolya", "Коля", "\"c3\""))
        val b = answer(character("ganya", "Ганя", "\"c1\"") + "," + character("kolya", "Коля", "\"c3\""), "[\"c2\"]")
        val combined = CastCheck.combine(listOf(a, b, a), r)
        val first = combined.getJSONArray("characters").getJSONObject(0)
        assertEquals("ganya", first.getString("id")); assertEquals(listOf("c1", "c2"), (0 until 2).map { first.getJSONArray("candidates").getString(it) })
        val minority = CastCheck.combine(listOf(a, b, b), r)
        assertEquals(listOf("c2"), (0 until minority.getJSONArray("other").length()).map { minority.getJSONArray("other").getString(it) })
    }
    @Test fun unverifiedUnsureAndRepeatedChecksCannotMerge() {
        val r = request(candidate("c1", "ганя", "m"), candidate("c2", "гаврила", "m"))
        val a = answer(character("g", "Ганя", "\"c1\",\"c2\""))
        for (v in listOf(null, checks(Triple("c1", "c2", "unsure")), checks(Triple("c1", "c2", "different")),
                checks(Triple("c1", "c2", "same"), Triple("c1", "c2", "same")), checks(Triple("c1", "c2", "same"), character = "other"))) {
            val cast = CastCheck.build(r, a, CastCheck.verdicts(v, a, r), "")
            assertEquals(listOf("c1"), cast.characters.single().refs); assertEquals(listOf("c2"), cast.other)
        }
        assertTrue(CastCheck.build(r, a, CastCheck.verdicts(checks(Triple("c1", "c2", "same")), a, r), "").other.isEmpty())
        assertFalse(CastCheck.verificationComplete(checks(), a, r)); assertTrue(CastCheck.verificationComplete(checks(Triple("c1", "c2", "unsure")), a, r))
    }
    @Test fun unsureIsEnoughForAShortNameInsideTheFullOne() {
        val r = request(candidate("c1", "аглая ивановна", "f", "Patr"), candidate("c2", "аглая", "f", "Name"))
        val a = answer(character("g", "Аглая Ивановна", "\"c1\",\"c2\"", "f"))
        assertEquals(listOf("c1", "c2"), CastCheck.build(r, a, CastCheck.verdicts(checks(Triple("c1", "c2", "unsure")), a, r), "").characters.single().refs)
    }
    @Test fun reliableGenderFamiliesAndDuplicatesGoToOthers() {
        val r = request(candidate("c1", "ганя", "m"), candidate("c2", "варя", "f", "Patr"), candidate("c3", "семья иволгин", kind = "family"),
            candidate("c4", "коля", "m", count = 20))
        val a = answer(character("g", "Ганя", "\"c1\",\"c2\",\"c3\"") + "," + character("k", "Коля", "\"c4\",\"c1\""))
        val cast = CastCheck.build(r, a, mapOf(Triple("g", "c1", "c2") to "same", Triple("g", "c1", "c3") to "same"), "")
        assertEquals(setOf("c1", "c2", "c3"), cast.other.toSet())
        assertEquals(listOf("k"), cast.characters.map { it.id })
    }
    @Test fun bareTitleSharedByTwoPeopleIsOther() {
        val first = candidate("c1", "иволгин", "m").apply { titles["генерал"] = 5 }
        val second = candidate("c2", "епанчин", "m").apply { titles["генерал"] = 5 }
        val title = candidate("c3", "генерал", "m", "Title", "title")
        val r = request(first, second, title)
        val a = answer(character("i", "Иволгин", "\"c1\",\"c3\"") + "," + character("e", "Епанчин", "\"c2\""))
        assertEquals(listOf("c3"), CastCheck.build(r, a, CastCheck.verdicts(checks(Triple("c1", "c3", "same"), character = "i"), a, r), "").other)
    }
    @Test fun inventedFullNameIsReplacedByBookWords() {
        val r = request(candidate("c1", "ганя"))
        assertEquals("Ганя", CastCheck.build(r, answer(character("g", "Гаврила (Ганя) Иванович", "\"c1\"")), emptyMap(), "").characters.single().name)
    }
    @Test fun travellingTitleIsDecidedChapterByChapter() {
        val people = setOf("epanchin", "ivolgin")
        val whos = listOf("epanchin", "epanchin", "epanchin", "ivolgin", "ivolgin", "unsure", "epanchin", "ivolgin", "ivolgin", "epanchin")
        val where = listOf("s1", "s1", "s1", "s2", "s2", "s2", "s3", "s3", "s4", "s5")
        val plan = CastCheck.titlePlan(whos, where, listOf("s1", "s2", "s3", "s4", "s5", "s6"), people)
        assertEquals("epanchin", plan["s1"]); assertEquals("ivolgin", plan["s2"])
        assertNull(plan["s3"])                       // one each: disagreement — «прочие»
        assertNull(plan["s6"])                       // never mentioned: as the whole book (no decision)
        // Passages against the main answer never move the title to another person.
        assertNull(CastCheck.titlePlan(whos, where, listOf("s2"), people, hint = "epanchin")["s2"])
    }
    @Test fun chaptersWithDifferentTitleOwnersGetVariantCastsWithOneVoicePerCharacter() {
        val epanchin = candidate("c1", "епанчин", "m"); val ivolgin = candidate("c2", "иволгин", "m")
        val general = candidate("c3", "генерал", "m", "Title", "title").apply {
            speaker = 3; contexts = List(6) { "…[[генерал]]…" }; contextSections = listOf("s1", "s1", "s1", "s2", "s2", "s2")
        }
        val r = request(epanchin, ivolgin, general, sections = listOf("s1", "s2"))
        val a = answer(character("epanchin", "Епанчин", "\"c1\"") + "," + character("ivolgin", "Иволгин", "\"c2\""), "[\"c3\"]")
        assertEquals(listOf("" to "c3"), CastCheck.labelTargets(r, a))
        val whos = mapOf(("" to "c3") to listOf("epanchin", "epanchin", "epanchin", "ivolgin", "ivolgin", "ivolgin"))
        val casts = CastCheck.casts(r, a, emptyMap(), whos, false)
        assertEquals(2, casts.size)
        assertEquals(listOf("c1", "c3"), casts[0].characters.single { it.id == "epanchin" }.refs)
        assertEquals(listOf("c2", "c3"), casts[1].characters.single { it.id == "ivolgin" }.refs)
        val plan = BookPreparationPlan("Тест", "", "f", "c", listOf(EpubBook.Section("s1", "I"), EpubBook.Section("s2", "II")), false,
            listOf(r), mapOf("s1" to listOf("0000000000000001")))
        val pkg = BookPackage.parse(plan.export(listOf(casts)))
        assertEquals(2, pkg.casts.size); assertEquals(1, pkg.groups.size)
        assertEquals(listOf("epanchin", "ivolgin"), pkg.groupCast(0).characters.map { it.id })
    }
    @Test fun labelVotesNeedEveryNumberOnce() {
        val ok = JSONObject("""{"answers":[{"n":2,"who":"b"},{"n":1,"who":"a"}]}""")
        assertEquals(listOf("a", "b"), CastCheck.labelVotes(ok, 2))
        assertNull(CastCheck.labelVotes(JSONObject("""{"answers":[{"n":1,"who":"a"},{"n":1,"who":"b"}]}"""), 2))
        assertNull(CastCheck.labelVotes(ok, 3))
    }
    @Test fun pipelineToleratesOneFailedVoteAndIncompleteVerification() {
        val r = request(candidate("c1", "ганя", "m"), candidate("c2", "гаврила", "m"))
        val plan = BookPreparationPlan("Тест", "", "f", "c", listOf(EpubBook.Section("s1", "I")), false, listOf(r), emptyMap())
        val main = answer(character("g", "Ганя", "\"c1\",\"c2\""))
        var calls = 0
        val casts = CastPipeline(plan, true, { name, _, kind, _, accept ->
            calls++
            when {
                name.endsWith("run2") -> throw IllegalStateException("LLM не вернула ответ")
                kind == CastCheck.Kind.MAIN -> main
                name.endsWith(".verify") -> checks().also { assertFalse(accept(it)) }.let { throw IllegalStateException("неполно") }
                else -> checks(Triple("c1", "c2", "same"))
            }
        }).run()
        assertEquals(listOf("c1", "c2"), casts.single().single().characters.single().refs)
        assertEquals(5, calls)
    }
}
