package com.brahmadeo.supertonic.tts.article

import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URI

data class ReadingArticle(val title: String, val text: String, val nextUrl: String? = null, val sourceUrl: String = "")

/** HTML is parsed locally. Scripts are never executed and HTML never goes to the LLM. */
object ArticleExtractor {
    const val MAX_TEXT = 180_000 // Keeps the existing playback Binder handoff below 1 MiB.
    private val urlPattern = Regex("https?://[^\\s<>\"\\u0000-\\u001f]+", RegexOption.IGNORE_CASE)
    fun sharedUrl(text: String): String? {
        if (text.length > 4096) return null // A long selected article containing links is already text.
        var value = urlPattern.find(text.trim())?.value?.trimEnd('.', ',', ';', '!', '»') ?: return null
        for ((open, close) in listOf('(' to ')', '[' to ']')) {
            while (value.endsWith(close) && value.count { it == close } > value.count { it == open }) value = value.dropLast(1)
        }
        return value.takeIf { validUrl(it) }
    }
    fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    fun extract(html: String, url: String): ReadingArticle {
        val doc = Jsoup.parse(html, url)
        // Bound adversarial/deep markup before Readability and our text traversal.
        val pending = java.util.ArrayDeque<Pair<org.jsoup.nodes.Node, Int>>()
        pending.add(doc to 0)
        var nodes = 0
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            require(++nodes <= 50_000 && depth <= 200) { "Разметка страницы слишком сложная. Передайте сам текст статьи." }
            node.childNodes().forEach { pending.add(it to depth + 1) }
        }
        require(doc.selectFirst("#challenge-running, #cf-challenge-running, .cf-error-details") == null) {
            "Сайт требует проверки в браузере. Скопируйте текст статьи и поделитесь им с MyTTS."
        }
        val wiki = doc.selectFirst(".mw-parser-output") ?: doc.selectFirst("#mw-content-text")
        if (wiki != null && (doc.body().hasClass("page-Заглавная_страница") || URI(url).path in listOf("", "/"))) {
            error("Это главная страница. Откройте отдельный рассказ и поделитесь его ссылкой.")
        }
        val title = (doc.selectFirst("#firstHeading, article h1, h1")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: doc.title()).trim()
        val explicit = doc.selectFirst(".tl_article_content") ?: wiki
        val nextUrl = ArticleContinuation.find(doc, explicit, url, title)
        val root = if (explicit != null) explicit.clone() else {
            val article = Readability4J(url, doc.outerHtml()).parse()
            val content = article.content ?: error("Не удалось выделить статью. Попробуйте поделиться выделенным текстом.")
            Jsoup.parseBodyFragment(content).body()
        }
        root.select("script, style, noscript, nav, footer, form, button, iframe, svg, canvas, " +
            "[hidden], [aria-hidden=true], .toc, #toc, .mw-editsection, .mw-empty-elt, .catlinks, " +
            ".navbox, .metadata, .infobox, .thumb, .reflist, .references, sup.reference, " +
            ".printfooter, .noprint, .mw-jump-link, .tl_article_header, .tl_article_footer, " +
            ".social-share, .share-buttons, .advertisement, .ads, .advert, .comments, #comments, .rating_box, .rating_target, .w4g_rb_nojs").remove()
        if (root.hasClass("tl_article_content")) {
            root.select("address").remove()
            root.selectFirst("p")?.let { first ->
                val clone = first.clone()
                clone.select("a").filter { it.text().startsWith("http") }.forEach { it.remove() }
                if (clone.text().trim() == title) first.remove()
            }
        }
        root.select("h1").remove() // Title is prepended once below.
        if (nextUrl != null) root.select("a[href]").filter {
            runCatching { ArticleContinuation.canonicalUrl(it.absUrl("href")) == nextUrl }.getOrDefault(false)
        }.forEach { anchor ->
            val parent = anchor.parent()
            anchor.remove()
            if (parent?.normalName() in setOf("p", "li") && parent?.text().isNullOrBlank()) parent?.remove()
        }
        val out = StringBuilder()
        val blocks = setOf("p", "div", "section", "article", "blockquote", "li", "h2", "h3", "h4", "pre", "tr")
        fun visit(node: org.jsoup.nodes.Node) {
            when (node) {
                is TextNode -> out.append(node.text())
                is Element -> {
                    val tag = node.normalName()
                    if (tag == "br" || tag == "hr") out.append('\n')
                    else {
                        if (tag in blocks) out.append('\n')
                        node.childNodes().forEach { visit(it) }
                        if (tag in blocks) out.append('\n')
                    }
                }
            }
        }
        visit(root)
        val paragraphs = out.toString().replace('\u00a0', ' ').lineSequence()
            .map { it.replace(Regex("[\\t\\r ]+"), " ").trim() }.filter { it.isNotBlank() }.toList()
        val body = paragraphs.joinToString("\n\n")
        require(body.length >= 80) { "На странице недостаточно текста статьи. Можно вставить или передать текст напрямую." }
        val text = if (title.isNotBlank() && paragraphs.firstOrNull() != title) "$title.\n\n$body" else body
        require(text.length <= MAX_TEXT) { "Статья слишком большая (лимит 180 000 знаков). Передайте её частями." }
        return ReadingArticle(title, text, nextUrl, url)
    }
}
