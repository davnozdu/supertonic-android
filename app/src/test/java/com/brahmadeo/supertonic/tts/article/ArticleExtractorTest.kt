package com.brahmadeo.supertonic.tts.article

import org.junit.Assert.*
import org.junit.Test

class ArticleExtractorTest {
    private val para = "— Анна, ты вернёшься? — спросил он. Она ответила: «Да, завтра!» И они пошли домой. "
    @Test fun browserShareTitleAndUrl() {
        assertEquals("https://telegra.ph/Dochki-materi-09-21-2", ArticleExtractor.sharedUrl("Дочки-матери\nhttps://telegra.ph/Dochki-materi-09-21-2"))
        assertEquals("https://example.org/wiki/Story_(Author)", ArticleExtractor.sharedUrl("https://example.org/wiki/Story_(Author)"))
        assertEquals("https://example.org/a", ArticleExtractor.sharedUrl("(https://example.org/a)"))
        assertFalse(ArticleExtractor.validUrl("file:///etc/passwd"))
        assertFalse(ArticleExtractor.validUrl("https://user:password@example.org"))
        assertNull(ArticleExtractor.sharedUrl(para.repeat(60) + "https://example.org"))
    }
    @Test fun telegraphKeepsDialogueAndParagraphs() {
        val result = ArticleExtractor.extract("<title>Story</title><article><h1>Рассказ</h1><div class='tl_article_content'><p>$para<a href='/'>ещё</a> текст</p><p>Вторая строка &amp; продолжение.</p></div><footer>SHARE JUNK</footer></article>", "https://telegra.ph/a")
        assertTrue(result.text.startsWith("Рассказ.\n\n— Анна"))
        assertTrue(result.text.contains("ещё текст\n\nВторая строка & продолжение."))
        assertFalse(result.text.contains("SHARE JUNK"))
    }
    @Test fun wikiRemovesNavigationFootnotesButKeepsLastParagraph() {
        val html = "<h1 id='firstHeading'>Ритуал</h1><nav>MENU JUNK</nav><div class='mw-parser-output'><div id='toc'>TOC JUNK</div><p>$para<sup class='reference'>[1]</sup></p><div class='noprint'>TOOLS JUNK</div><p>Последняя фраза. Конец!</p><div class='reflist'>REF JUNK</div></div><div class='catlinks'>CATEGORY JUNK</div>"
        val result = ArticleExtractor.extract(html, "https://mrakopedia.net/wiki/Story")
        assertTrue(result.text.endsWith("Последняя фраза. Конец!"))
        assertFalse(result.text.contains("JUNK")); assertFalse(result.text.contains("[1]"))
    }
    @Test fun genericReadabilityKeepsStoryWithoutMenu() {
        val html = "<title>Рассказ</title><nav>MENU JUNK</nav><article><h1>Рассказ</h1>" + (1..8).joinToString("") { "<p>$para $para</p>" } + "</article><footer>FOOTER JUNK</footer>"
        val result = ArticleExtractor.extract(html, "https://example.org/story")
        assertTrue(result.text.contains(para.trim())); assertFalse(result.text.contains("JUNK"))
    }
    @Test fun rejectHomepageChallengeAndOversizedArticle() {
        for ((html, url) in listOf(
            "<div class='mw-parser-output'><p>$para</p></div>" to "https://mrakopedia.net/",
            "<div id='challenge-running'>Challenge</div>" to "https://example.org/story",
            "<div class='tl_article_content'><p>${para.repeat(2500)}</p></div>" to "https://telegra.ph/a"
        )) {
            try { ArticleExtractor.extract(html, url); fail("Expected rejected page") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
        }
    }
    @Test fun rejectDeepMarkupWithoutCrashing() {
        val html = "<div>".repeat(220) + para + "</div>".repeat(220)
        try { ArticleExtractor.extract(html, "https://example.org/a"); fail("Expected depth limit") }
        catch (_: IllegalArgumentException) {}
    }
}
