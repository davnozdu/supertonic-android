package com.brahmadeo.supertonic.tts.books.prepare

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

/** Pure JVM EPUB reader. No extracted files, scripts, external entities or network access. */
data class EpubBook(val title: String, val author: String, val sections: List<Section>, val paragraphs: List<Paragraph>) {
    data class Section(val id: String, val title: String)
    data class Paragraph(val section: Int, val text: String)

    companion object {
        const val MAX_BYTES = 50L * 1024 * 1024
        private const val MAX_EXPANDED = 160L * 1024 * 1024
        private const val MAX_ENTRY = 16 * 1024 * 1024
        private val blocks = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li")
        private val spaces = Regex("[\\s\\p{Z}\\u0085\\u001c-\\u001f]+")
        fun clean(text: String) = text.replace("́", "").replace(spaces, " ").trim()
        private fun tag(e: Element) = e.tagName().substringAfter(':')
        // BeautifulSoup's get_text(" "): each text node, including inline markup, is separated.
        private fun text(e: Element): String = clean(buildList<String> {
                fun visit(node: org.jsoup.nodes.Node) {
                    if (node is TextNode) add(node.wholeText) else node.childNodes().forEach(::visit)
                }
                visit(e)
            }.joinToString(" "))

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

        fun read(file: File, check: () -> Unit = {}): EpubBook {
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
                val title = all.firstOrNull { tag(it) == "title" && text(it).isNotBlank() }?.let(::text) ?: file.nameWithoutExtension
                val author = all.firstOrNull { tag(it) == "creator" }?.let(::text).orEmpty()
                val items = all.filter { tag(it) == "item" }.associateBy { it.attr("id") }
                val spine = all.filter { tag(it) == "itemref" }.mapNotNull { items[it.attr("idref")]?.attr("href") }
                val toc = mutableListOf<Pair<String, String>>()
                val nav = items.values.firstOrNull { "nav" in it.attr("properties").split(' ') }
                if (nav != null) {
                    val href = nav.attr("href")
                    val doc = read(path(opfPath, href))
                    val node = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: doc.selectFirst("nav")
                    node?.selectFirst("ol")?.children()?.filter { tag(it) == "li" }?.forEach { li ->
                        li.selectFirst("a[href]")?.let { toc += text(it) to path(href, it.attr("href")) }
                    }
                }
                if (toc.isEmpty()) {
                    val ncxId = all.firstOrNull { tag(it) == "spine" }?.attr("toc")
                    val ncx = ncxId?.let { items[it] } ?: items.values.firstOrNull { it.attr("href").endsWith(".ncx") }
                    if (ncx != null) {
                        val href = ncx.attr("href")
                        val doc = read(path(opfPath, href), true)
                        doc.getAllElements().firstOrNull { tag(it) == "navMap" }?.children()?.filter { tag(it) == "navPoint" }?.forEach { point ->
                            val label = point.getAllElements().firstOrNull { tag(it) == "text" }?.let(::text).orEmpty()
                            point.children().firstOrNull { tag(it) == "content" }?.let { toc += label to path(href, it.attr("src")) }
                        }
                    }
                }
                if (toc.isEmpty()) spine.forEach { toc += it.substringAfterLast('/').substringBeforeLast('.') to path("", it) }
                require(toc.isNotEmpty() && toc.size <= 2000) { "В EPUB нет разделов или оглавление слишком велико" }
                val sections = toc.mapIndexed { i, (label, _) -> Section("s${i + 1}", label) }
                val starts = toc.withIndex().groupBy { it.value.second.substringBefore('#') }
                val paragraphs = mutableListOf<Paragraph>()
                var current = 0
                var chars = 0L
                for (href in spine) {
                    if (!href.substringBefore('#').lowercase().endsWithAny(".xhtml", ".html", ".htm")) continue
                    val pending = starts[path("", href)]?.associate { it.value.second.substringAfter('#', "") to it.index }?.toMutableMap() ?: mutableMapOf()
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
                            paragraphs += Paragraph(current, t)
                        }
                    }
                }
                require(paragraphs.isNotEmpty()) { "В EPUB не найден текст книги" }
                val used = paragraphs.map { it.section }.distinct().sorted()
                val renumber = used.withIndex().associate { it.value to it.index }
                return EpubBook(title, author, used.mapIndexed { i, old -> sections[old].copy(id = "s${i + 1}") },
                    paragraphs.map { it.copy(section = renumber.getValue(it.section)) })
            }
        }
        private fun String.endsWithAny(vararg suffixes: String) = suffixes.any(::endsWith)
    }
}
