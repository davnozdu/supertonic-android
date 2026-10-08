package com.brahmadeo.supertonic.tts.books

import com.brahmadeo.supertonic.tts.books.prepare.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Fb2BookTest {
    private val body = """<body><section><title><p>Первый рассказ</p></title><p>Вошёл <strong>Семён</strong> и спросил о книге.</p>
        <section><title><p>Вложенная глава</p></title><p>— Да, — сказала Алёна.</p></section></section>
        <section><title><p>Второй рассказ</p></title><p>Другое длинное предложение для проверки отпечатков.</p></section></body>"""
    private fun document(body: String = this.body, encoding: String = "UTF-8") = """<?xml version="1.0" encoding="$encoding"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info>
        <book-title>Книга</book-title><author><first-name>Имя</first-name><middle-name>Отчество</middle-name><last-name>Фамилия</last-name></author>
        </title-info></description>$body<body name="notes"><section><p>Сноска не должна попасть в персонажи.</p></section></body>
        <binary id="cover" content-type="image/png">AAAA</binary></FictionBook>"""
    private fun withBook(bytes: ByteArray, test: (File) -> Unit) {
        val file = File.createTempFile("fb2-test", ".book")
        try { file.writeBytes(bytes); test(file) } finally { file.delete() }
    }

    @Test fun readsMetadataNestedSectionsAndExcludesNotes() = withBook(document().toByteArray()) { file ->
        val b = BookInput.read(file)
        assertEquals("Книга", b.title); assertEquals("Имя Отчество Фамилия", b.author)
        assertEquals(listOf("Первый рассказ", "Второй рассказ"), b.sections.map { it.title })
        assertEquals(6, b.paragraphs.size); assertEquals(listOf(0, 0, 0, 0, 1, 1), b.paragraphs.map { it.section })
        assertEquals("Вошёл Семён и спросил о книге.", b.paragraphs[1].text)
        assertTrue(b.paragraphs.none { "Сноска" in it.text || "AAAA" in it.text })
    }
    @Test fun honoursWindows1251AndUtf16() {
        for (encoding in listOf("windows-1251", "UTF-16")) withBook(document(encoding = encoding).toByteArray(charset(encoding))) {
            val b = BookInput.read(it); assertEquals("Книга", b.title); assertTrue(b.paragraphs[1].text.contains("Семён"))
        }
    }
    @Test fun singleUntitledWrapperIsUnwrapped() {
        val sections = body.removePrefix("<body>").removeSuffix("</body>")
        withBook(document("<body><section>$sections</section></body>").toByteArray()) {
            assertEquals(2, BookInput.read(it).sections.size)
        }
    }
    @Test fun titlelessBookAndVerseAreNotLost() = withBook(document("<body><section><poem><stanza><v>Первая строка стихотворения</v><v>Вторая строка стихотворения</v></stanza></poem></section></body>").toByteArray()) {
        val b = BookInput.read(it); assertEquals(1, b.sections.size); assertEquals(2, b.paragraphs.size)
    }
    @Test fun readsSingleFb2ZipAndRejectsAmbiguousArchive() {
        val file = File.createTempFile("fb2-zip", ".book")
        try {
            for (count in listOf(1, 2, 0)) {
                ZipOutputStream(file.outputStream()).use { z -> repeat(count) { n ->
                    z.putNextEntry(ZipEntry("nested/book$n.FB2")); z.write(document().toByteArray()); z.closeEntry()
                }; if (count == 0) { z.putNextEntry(ZipEntry("image.jpg")); z.write(byteArrayOf(1)); z.closeEntry() } }
                if (count == 1) assertEquals("Книга", BookInput.read(file).title)
                else assertTrue(runCatching { BookInput.read(file) }.isFailure)
            }
        } finally { file.delete() }
    }
    @Test fun rejectsUnrelatedXmlAndNotesOnly() {
        for (xml in listOf("<html><p>Not a book</p></html>", document(""))) withBook(xml.toByteArray()) {
            assertTrue(runCatching { BookInput.read(it) }.isFailure)
        }
    }
    @Test fun cancellationInterruptsFb2Reading() = withBook(document().toByteArray()) {
        assertTrue(runCatching { BookInput.read(it) { throw java.util.concurrent.CancellationException() } }.isFailure)
    }
}
