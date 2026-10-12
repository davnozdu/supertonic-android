package com.brahmadeo.supertonic.tts.books.prepare

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

/** Pure JVM EPUB reader. No extracted files, scripts, external entities or network access.
 * [notes]: paragraphs that are footnotes (fingerprinted, never analysed for characters); [analysis]: paragraph text
 * without footnote markers («Рогожин¹»), only where it differs. */
data class EpubBook(val title: String, val author: String, val sections: List<Section>, val paragraphs: List<Paragraph>,
                    val notes: Set<Int> = emptySet(), val analysis: Map<Int, String> = emptyMap()) {
    data class Section(val id: String, val title: String)
    data class Paragraph(val section: Int, val text: String)

    companion object {
        const val MAX_BYTES = 50L * 1024 * 1024
        /** All levels of a table of contents (chapters of a novel). */
        const val ALL_LEVELS = 99
        private const val MAX_EXPANDED = 160L * 1024 * 1024
        private const val MAX_ENTRY = 16 * 1024 * 1024
        private val blocks = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li")
        private val spaces = Regex("[\\s\\p{Z}\\u0085\\u001c-\\u001f]+")
        fun clean(text: String) = text.replace("́", "").replace(spaces, " ").trim()
        private fun tag(e: Element) = e.tagName().substringAfter(':').lowercase()
        private val NOTEREF_TEXT = Regex("^\\s*[\\[(]?\\s*(?:\\d{1,3}|[*†‡]+|[ivx]{1,4})\\s*[\\])]?\\s*$", RegexOption.IGNORE_CASE)
        /** A section made only of notes: the last level of its title is just this word. */
        val NOTES_TITLE = Regex("(?:^|[\\s.])(?:примечания|примечание|сноски|комментарии|notes|endnotes|footnotes)\\.?\\s*$", RegexOption.IGNORE_CASE)
        private val NOTE_CLASS = Regex("(?:^|[\\s_-])(?:foot|end|rear)?notes?(?:$|[\\s_-])|snoska|sноск|primech|komment", RegexOption.IGNORE_CASE)
        private val NOTE_TYPES = setOf("footnote", "endnote", "rearnote", "note", "footnotes", "endnotes", "rearnotes")

        // BeautifulSoup's get_text(" "): each text node, including inline markup, is separated.
        internal fun text(e: Element, skip: (Node) -> Boolean = { false }): String = clean(buildList<String> {
                fun visit(node: Node) {
                    if (skip(node)) return
                    if (node is TextNode) add(node.wholeText) else node.childNodes().forEach(::visit)
                }
                visit(e)
            }.joinToString(" "))

        /** A footnote marker: <a epub:type="noteref">, <sup>1</sup>, <a href="#n1">[1]</a>, FB2 <a type="note">. */
        internal fun noteref(node: Node): Boolean {
            if (node !is Element || node.tagName().substringAfter(':').lowercase() !in setOf("sup", "a")) return false
            val kind = node.attr("epub:type") + " " + node.attr("role") + " " + node.attr("type")
            return "noteref" in kind || kind.split(' ').any { it == "note" } || NOTEREF_TEXT.matches(node.text())
        }

        /** Inside a footnote: <aside>, epub:type="footnote|endnote|…", class note/footnote/snoska. */
        private fun isNote(e: Element): Boolean {
            var node: Element? = e
            while (node != null) {
                if (tag(node) == "aside") return true
                val kind = node.attr("epub:type").ifBlank { node.attr("type") }
                if (kind.split(' ').any { it in NOTE_TYPES }) return true
                if (node.classNames().any { NOTE_CLASS.containsMatchIn(it) }) return true
                node = node.parent()
            }
            return false
        }

        private fun path(origin: String, href: String): String {
            require(!href.contains(":") && !href.startsWith("/")) { "В EPUB указан внешний путь" }
            val joined = origin.substringBeforeLast('/', "").let { if (it.isEmpty()) href else "$it/$href" }
            val out = mutableListOf<String>()
            for (part in joined.split('/')) when (part) {
                "", "." -> Unit
                ".." -> { require(out.isNotEmpty()) { "Некорректный путь EPUB" }; out.removeAt(out.lastIndex) }
                else -> out += part
            }
            return out.joinToString("/")
        }

        /** [depth] — levels of the table of contents used as sections: 1 is the top level (stories of a
         * collection, parts of a novel), [ALL_LEVELS] gives chapters titled with their path («ЧАСТЬ ПЕРВАЯ. I.»). */
        fun read(file: File, check: () -> Unit = {}, depth: Int = 1): EpubBook {
            require(file.length() in 1..MAX_BYTES) { "EPUB пустой или больше 50 МБ" }
            var expanded = 0L
            ZipFile(file).use { zip ->
                fun read(name: String, xml: Boolean = false): Element {
                    check()
                    val entry = zip.getEntry(name) ?: error("В EPUB отсутствует файл $name")
                    require(entry.size <= MAX_ENTRY) { "Слишком большой раздел EPUB" }
                    val bytes = zip.getInputStream(entry).use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            check()
                            val n = stream.read(buf); if (n < 0) break
                            expanded += n
                            require(out.size() + n <= MAX_ENTRY && expanded <= MAX_EXPANDED) { "EPUB слишком велик после распаковки" }
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                    val source = bytes.toString(Charsets.UTF_8)
                    // XHTML permits <title/>. The HTML parser treats that as an unclosed title
                    // and would swallow the entire body (common in FB2-generated EPUBs).
                    val xhtml = source.trimStart().startsWith("<?xml") || source.contains("http://www.w3.org/1999/xhtml")
                    return Jsoup.parse(source, "", if (xml || xhtml) Parser.xmlParser() else Parser.htmlParser())
                }
                val container = read("META-INF/container.xml", true)
                val opfPath = container.getAllElements().firstOrNull { tag(it) == "rootfile" }?.attr("full-path")
                    ?.takeIf { it.isNotBlank() } ?: error("В EPUB нет описания книги")
                val opf = read(path("", opfPath), true)
                val all = opf.getAllElements()
                val title = all.firstOrNull { tag(it) == "title" && text(it).isNotBlank() }?.let { text(it) } ?: file.nameWithoutExtension
                val author = all.firstOrNull { tag(it) == "creator" }?.let { text(it) }.orEmpty()
                val items = all.filter { tag(it) == "item" }.associateBy { it.attr("id") }
                val refs = all.filter { tag(it) == "itemref" && items.containsKey(it.attr("idref")) }
                val spine = refs.map { items.getValue(it.attr("idref")).attr("href") }
                // Spine items outside the reading order (linear="no") are notes and appendices.
                val aside = refs.filter { it.attr("linear") == "no" }.map { path("", items.getValue(it.attr("idref")).attr("href")) }.toSet()
                val toc = mutableListOf<Pair<String, String>>()
                val nav = items.values.firstOrNull { "nav" in it.attr("properties").split(' ') }
                if (nav != null) {
                    val href = nav.attr("href")
                    val doc = read(path(opfPath, href))
                    val node = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: doc.selectFirst("nav")
                    fun walk(ol: Element, trail: List<String>) {
                        for (li in ol.children().filter { tag(it) == "li" }) {
                            check()
                            val a = li.children().firstOrNull { tag(it) == "a" } ?: li.selectFirst("a")
                            val label = a?.let { text(it) }.orEmpty()
                            if (a != null && a.attr("href").isNotBlank()) toc += (trail + label).joinToString(" ") to path(href, a.attr("href"))
                            val child = li.children().firstOrNull { tag(it) == "ol" }
                            if (trail.size + 1 < depth && child != null) walk(child, if (label.isNotEmpty()) trail + label else trail)
                        }
                    }
                    node?.selectFirst("ol")?.let { walk(it, emptyList()) }
                }
                if (toc.isEmpty()) {
                    val ncxId = all.firstOrNull { tag(it) == "spine" }?.attr("toc")
                    val ncx = ncxId?.let { items[it] } ?: items.values.firstOrNull { it.attr("href").endsWith(".ncx") }
                    if (ncx != null) {
                        val href = ncx.attr("href")
                        val doc = read(path(opfPath, href), true)
                        fun walk(parent: Element, trail: List<String>) {
                            for (point in parent.children().filter { tag(it) == "navpoint" }) {
                                check()
                                val label = point.children().firstOrNull { tag(it) == "navlabel" }
                                    ?.getAllElements()?.firstOrNull { tag(it) == "text" }?.let { text(it) }.orEmpty()
                                point.children().firstOrNull { tag(it) == "content" }?.let { toc += (trail + label).joinToString(" ") to path(href, it.attr("src")) }
                                if (trail.size + 1 < depth) walk(point, if (label.isNotEmpty()) trail + label else trail)
                            }
                        }
                        doc.getAllElements().firstOrNull { tag(it) == "navmap" }?.let { walk(it, emptyList()) }
                    }
                }
                if (toc.isEmpty()) spine.forEach { toc += it.substringAfterLast('/').substringBeforeLast('.') to path("", it) }
                require(toc.isNotEmpty() && toc.size <= 5000) { "В EPUB нет разделов или оглавление слишком велико" }
                val sections = toc.mapIndexed { i, (label, _) -> Section("s${i + 1}", label.trim()) }
                val starts = toc.withIndex().groupBy { it.value.second.substringBefore('#') }
                val paragraphs = mutableListOf<Paragraph>()
                val notes = mutableSetOf<Int>()
                val analysis = mutableMapOf<Int, String>()
                var current = 0
                var chars = 0L
                for (href in spine) {
                    if (!href.substringBefore('#').lowercase().endsWithAny(".xhtml", ".html", ".htm")) continue
                    val file = path("", href)
                    val pending = starts[file]?.associate { it.value.second.substringAfter('#', "") to it.index }?.toMutableMap() ?: mutableMapOf()
                    pending.remove("")?.let { current = it }
                    val doc = read(path(opfPath, href))
                    for (e in doc.getAllElements()) {
                        check()
                        pending.remove(e.id().takeIf { it.isNotBlank() })?.let { current = it }
                        if (tag(e) !in blocks || e.getAllElements().drop(1).any { tag(it) in blocks }) continue
                        val t = text(e)
                        if (t.isNotEmpty()) {
                            chars += t.length
                            require(chars <= 12_000_000 && paragraphs.size < 100_000) { "Текст EPUB слишком велик" }
                            val bare = text(e) { node -> noteref(node) }
                            if (bare != t) analysis[paragraphs.size] = bare
                            if (file in aside || NOTES_TITLE.containsMatchIn(sections[current].title) || isNote(e)) notes += paragraphs.size
                            paragraphs += Paragraph(current, t)
                        }
                    }
                }
                require(paragraphs.isNotEmpty()) { "В EPUB не найден текст книги" }
                val used = paragraphs.map { it.section }.distinct().sorted()
                val renumber = used.withIndex().associate { it.value to it.index }
                return EpubBook(title, clean(author), used.mapIndexed { i, old -> sections[old].copy(id = "s${i + 1}") },
                    paragraphs.map { it.copy(section = renumber.getValue(it.section)) }, notes, analysis)
            }
        }
        private fun String.endsWithAny(vararg suffixes: String) = suffixes.any(::endsWith)
    }
}
