package com.brahmadeo.supertonic.tts.article

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/** Follow only explicit continuation links, never a recommendation or arbitrary story link. */
object ArticleContinuation {
    private val nextLabel = Regex("^(?:далее|дальше|читать дальше|продолжение|продолжение здесь|продолжить чтение|читать продолжение|следующая (?:часть|страница|глава)|next(?: page| part| chapter)?|continue(?: reading)?|(?:читать )?(?:часть|глава) \\d+)(?:[ .:→»›>-]*)$", RegexOption.IGNORE_CASE)
    private val partNumber = Regex("(?:часть|глава|part|chapter)\\s*(\\d+)", RegexOption.IGNORE_CASE)
    fun canonicalUrl(value: String): String = URI(value.substringBefore('#')).normalize().toASCIIString()
    fun find(doc: Document, articleRoot: Element?, url: String, title: String): String? {
        val origin = URI(url)
        fun allowed(href: String): String? = runCatching {
            val target = origin.resolve(href.trim()).normalize()
            if (!ArticleExtractor.validUrl(target.toString()) || !target.host.equals(origin.host, true) ||
                (origin.scheme == "https" && target.scheme != "https") || target.path == origin.path && target.query == origin.query ||
                target.path.startsWith("/tag/") || target.path.startsWith("/author/")) return@runCatching null
            canonicalUrl(target.toString())
        }.getOrNull()
        val explicit = doc.select("link[rel=next], a[rel=next]").mapNotNull { allowed(it.attr("href")) }.distinct()
        if (explicit.size == 1) return explicit.single()
        if (explicit.size > 1) return null
        val root = articleRoot ?: doc.selectFirst("article, main") ?: return null
        val currentPart = partNumber.find(title)?.groupValues?.get(1)?.toIntOrNull()
        fun seriesTitle(value: String) = partNumber.replace(value, "").replace(Regex("[\\p{P}\\s]+"), " ").trim().lowercase()
        val paragraphs = root.select("p, li, div, h2, h3, h4, h5, h6")
        val tail = paragraphs.takeLast(6)
        val candidates = root.select("a[href]").filter { a ->
            val label = a.text().replace(Regex("\\s+"), " ").trim().trimStart('→', '»', '›', ' ')
            val number = partNumber.find(label)?.groupValues?.get(1)?.toIntOrNull()
            val namedNext = currentPart != null && number == currentPart + 1 &&
                seriesTitle(title).length >= 4 && seriesTitle(label) == seriesTitle(title)
            if (!nextLabel.matches(label) && !namedNext) return@filter false
            if (a.parents().any { ancestor -> ancestor.normalName() in setOf("nav", "footer") ||
                ancestor.classNames().any { it in setOf("related", "recommended", "comments", "advertisement") } }) return@filter false
            // A table of contents at the beginning is not a next-page link.
            if (!namedNext && tail.none { it === a.parent() || it.getAllElements().contains(a) }) return@filter false
            if (number != null && number != (currentPart ?: 1) + 1) return@filter false
            true
        }.mapNotNull { allowed(it.attr("href")) }.distinct()
        return candidates.singleOrNull()
    }
}

/** A generation token prevents a cancelled download from extending a newer article. */
object ArticleSession {
    @Volatile var pending = false; private set
    private var token = 0L
    private var cancelWork: (() -> Unit)? = null
    @Synchronized fun begin(cancel: () -> Unit): Long {
        cancelWork?.invoke(); token++; pending = true; cancelWork = cancel
        return token
    }
    @Synchronized fun current(id: Long) = id == token
    @Synchronized fun complete(id: Long) { if (id == token) { pending = false; cancelWork = null } }
    @Synchronized fun cancel() { token++; pending = false; cancelWork?.invoke(); cancelWork = null }
}
