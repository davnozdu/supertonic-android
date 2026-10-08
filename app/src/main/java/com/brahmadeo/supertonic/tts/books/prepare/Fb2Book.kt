package com.brahmadeo.supertonic.tts.books.prepare

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** FB2 and FB2.ZIP: main body only, document order, top-level sections delimit stories/parts.
 * Jsoup's XML parser never fetches DTDs, links or base64 images. Encoding comes from XML/BOM. */
object Fb2Book {
    private val blocks = setOf("p", "subtitle", "v")
    private fun tag(e: Element) = e.tagName().substringAfter(':').lowercase()
    private fun text(e: Element): String {
        val parts = mutableListOf<String>()
        fun visit(n: Node) {
            if (n is TextNode) parts += n.wholeText
            else if (n !is Element || tag(n) !in setOf("binary", "image", "style")) n.childNodes().forEach(::visit)
        }
        visit(e)
        return EpubBook.clean(parts.joinToString(" "))
    }

    fun read(file: File, zipped: Boolean = false, check: () -> Unit = {}): EpubBook {
        require(file.length() in 1..EpubBook.MAX_BYTES) { "Файл книги пустой или больше 50 МБ" }
        if (!zipped) return file.inputStream().use { parse(it, file.nameWithoutExtension, check) }
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.lowercase().endsWith(".fb2") }.toList()
            require(entries.size == 1) { "В ZIP должна быть ровно одна книга FB2" }
            require(entries.single().size <= EpubBook.MAX_BYTES) { "FB2 больше 50 МБ после распаковки" }
            return zip.getInputStream(entries.single()).use { parse(it, entries.single().name.substringAfterLast('/').substringBeforeLast('.'), check) }
        }
    }

    private fun parse(input: InputStream, fallbackTitle: String, check: () -> Unit): EpubBook {
        var read = 0L
        val bounded = object : java.io.FilterInputStream(input) {
            override fun read(): Int {
                check(); val value = super.read()
                if (value >= 0) { read++; require(read <= EpubBook.MAX_BYTES) { "FB2 больше 50 МБ" } }
                return value
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                check(); val n = super.read(bytes, offset, length)
                if (n > 0) { read += n; require(read <= EpubBook.MAX_BYTES) { "FB2 больше 50 МБ" } }
                return n
            }
        }
        val doc = Jsoup.parse(bounded, null, "", Parser.xmlParser())
        val root = doc.children().firstOrNull { tag(it) == "fictionbook" } ?: error("Это не книга FB2")
        val info = root.children().firstOrNull { tag(it) == "description" }?.children()?.firstOrNull { tag(it) == "title-info" }
        val title = info?.children()?.firstOrNull { tag(it) == "book-title" }?.let(::text)?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val authors = info?.children()?.filter { tag(it) == "author" }?.map { author ->
            val fields = author.children().associateBy(::tag)
            val full = listOf("first-name", "middle-name", "last-name").mapNotNull { fields[it]?.let(::text)?.takeIf(String::isNotBlank) }.joinToString(" ")
            full.ifBlank { fields["nickname"]?.let(::text).orEmpty() }
        }?.filter { it.isNotBlank() }?.joinToString(", ").orEmpty()
        // FB2 notes live in additional named bodies; do not treat them as a story or dialogue.
        val body = root.children().firstOrNull { tag(it) == "body" && it.attr("name").isBlank() }
            ?: root.children().firstOrNull { tag(it) == "body" && it.attr("name").lowercase() !in setOf("notes", "comments", "footnotes") }
            ?: error("В FB2 нет основного текста")
        val sections = mutableListOf<EpubBook.Section>()
        val paragraphs = mutableListOf<EpubBook.Paragraph>()
        var chars = 0L
        fun addBlocks(container: Element, section: Int) {
            for (e in container.getAllElements()) {
                check()
                if (tag(e) !in blocks || e.getAllElements().drop(1).any { tag(it) in blocks }) continue
                val value = text(e)
                if (value.isEmpty()) continue
                chars += value.length
                require(chars <= 12_000_000 && paragraphs.size < 100_000) { "Текст FB2 слишком велик" }
                paragraphs += EpubBook.Paragraph(section, value)
            }
        }
        val children = body.children().filter { tag(it) == "section" }
        // A common FB2 layout wraps the entire book in one untitled section. Unwrap it,
        // preserving introductory text, so a collection still has separate top-level stories.
        val wrapper = children.singleOrNull()?.takeIf { e ->
            val heading = e.children().firstOrNull { tag(it) == "title" }?.let(::text).orEmpty()
            heading.isBlank() || heading.equals(title, ignoreCase = true)
        }
        val top = if (wrapper != null && wrapper.children().count { tag(it) == "section" } >= 2) wrapper else body
        var current = -1
        for (child in top.children()) {
            check()
            if (tag(child) == "section") {
                require(sections.size < 2000) { "В FB2 слишком много разделов" }
                val label = child.children().firstOrNull { tag(it) == "title" }?.let(::text).orEmpty()
                current = sections.size
                sections += EpubBook.Section("s${current + 1}", label.ifBlank { "Раздел ${current + 1}" })
                addBlocks(child, current)
            } else {
                // Front matter attaches to the following first section, like EPUB's pre-TOC text.
                addBlocks(child, maxOf(0, current))
            }
        }
        if (sections.isEmpty()) sections += EpubBook.Section("s1", title)
        require(paragraphs.isNotEmpty()) { "В FB2 не найден текст книги" }
        val used = paragraphs.map { it.section }.distinct().sorted()
        val renumber = used.withIndex().associate { it.value to it.index }
        return EpubBook(title, authors, used.mapIndexed { i, old -> sections[old].copy(id = "s${i + 1}") },
            paragraphs.map { it.copy(section = renumber.getValue(it.section)) })
    }
}

/** Format detection uses contents, because Android document providers often give a generic MIME type. */
object BookInput {
    fun read(file: File, check: () -> Unit = {}): EpubBook {
        check()
        val magic = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
            val epub = ZipFile(file).use { it.getEntry("META-INF/container.xml") != null }
            return if (epub) EpubBook.read(file, check) else Fb2Book.read(file, true, check)
        }
        return Fb2Book.read(file, check = check)
    }
}
