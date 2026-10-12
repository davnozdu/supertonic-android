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
    private fun text(e: Element, notes: Boolean = true): String {
        val parts = mutableListOf<String>()
        fun visit(n: Node) {
            if (!notes && EpubBook.noteref(n)) return
            if (n is TextNode) parts += n.wholeText
            else if (n !is Element || tag(n) !in setOf("binary", "image", "style")) n.childNodes().forEach(::visit)
        }
        visit(e)
        return EpubBook.clean(parts.joinToString(" "))
    }

    /** [depth] — levels of nested <section> used as sections (1: top level; [EpubBook.ALL_LEVELS]: chapters). */
    fun read(file: File, zipped: Boolean = false, check: () -> Unit = {}, depth: Int = 1): EpubBook {
        require(file.length() in 1..EpubBook.MAX_BYTES) { "Файл книги пустой или больше 50 МБ" }
        if (!zipped) return file.inputStream().use { parse(it, file.nameWithoutExtension, check, depth) }
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.lowercase().endsWith(".fb2") }.toList()
            require(entries.size == 1) { "В ZIP должна быть ровно одна книга FB2" }
            require(entries.single().size <= EpubBook.MAX_BYTES) { "FB2 больше 50 МБ после распаковки" }
            return zip.getInputStream(entries.single()).use { parse(it, entries.single().name.substringAfterLast('/').substringBeforeLast('.'), check, depth) }
        }
    }

    private fun parse(input: InputStream, fallbackTitle: String, check: () -> Unit, depth: Int): EpubBook {
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
        val analysis = mutableMapOf<Int, String>()
        var chars = 0L
        fun add(e: Element, section: Int) {
            val value = text(e)
            if (value.isEmpty()) return
            chars += value.length
            require(chars <= 12_000_000 && paragraphs.size < 100_000) { "Текст FB2 слишком велик" }
            val bare = text(e, notes = false)
            if (bare != value) analysis[paragraphs.size] = bare
            paragraphs += EpubBook.Paragraph(section, value)
        }
        fun heading(e: Element) = e.children().firstOrNull { tag(it) == "title" }?.let { text(it) }.orEmpty()
        fun open(e: Element, trail: List<String>): Pair<Int, List<String>> {
            require(sections.size < 5000) { "В FB2 слишком много разделов" }
            val label = heading(e)
            val path = if (label.isNotBlank()) trail + label else trail
            sections += EpubBook.Section("s${sections.size + 1}", path.joinToString(" ").ifBlank { "Раздел ${sections.size + 1}" })
            return sections.size - 1 to path
        }
        // Document order; a nested <section> opens a new section while within [depth] levels.
        fun walk(e: Element, section: Int, trail: List<String>, level: Int) {
            check()
            if (tag(e) in blocks) {
                if (e.getAllElements().drop(1).none { tag(it) in blocks }) { add(e, section); return }
            }
            for (child in e.children()) {
                if (tag(child) == "section" && level < depth) {
                    val (inner, path) = open(child, trail)
                    walk(child, inner, path, level + 1)
                } else walk(child, section, trail, level)
            }
        }
        val children = body.children().filter { tag(it) == "section" }
        // A common FB2 layout wraps the entire book in one untitled section. Unwrap it,
        // preserving introductory text, so a collection still has separate top-level stories.
        val wrapper = children.singleOrNull()?.takeIf { e ->
            val label = heading(e)
            label.isBlank() || label.equals(title, ignoreCase = true)
        }
        val top = if (wrapper != null && wrapper.children().count { tag(it) == "section" } >= 2) wrapper else body
        for (child in top.children()) {
            check()
            if (tag(child) == "section") {
                val (index, path) = open(child, emptyList())
                walk(child, index, path, 1)
            } else {
                // Front matter attaches to the following first section, like EPUB's pre-TOC text.
                walk(child, maxOf(0, sections.size - 1), emptyList(), depth)
            }
        }
        if (sections.isEmpty()) sections += EpubBook.Section("s1", title)
        require(paragraphs.isNotEmpty()) { "В FB2 не найден текст книги" }
        val used = paragraphs.map { it.section }.distinct().sorted()
        val renumber = used.withIndex().associate { it.value to it.index }
        return EpubBook(title, authors, used.mapIndexed { i, old -> sections[old].copy(id = "s${i + 1}") },
            paragraphs.map { it.copy(section = renumber.getValue(it.section)) }, analysis = analysis)
    }
}

/** Format detection uses contents, because Android document providers often give a generic MIME type. */
object BookInput {
    fun read(file: File, depth: Int = 1, check: () -> Unit = {}): EpubBook {
        check()
        val magic = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
            val epub = ZipFile(file).use { it.getEntry("META-INF/container.xml") != null }
            return if (epub) EpubBook.read(file, check, depth) else Fb2Book.read(file, true, check, depth)
        }
        return Fb2Book.read(file, check = check, depth = depth)
    }
}
