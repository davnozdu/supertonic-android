package com.brahmadeo.supertonic.tts.article

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ArticleContinuationTest {
    private fun find(html: String, title: String = "Рассказ · Часть 1"): String? {
        val doc = Jsoup.parse(html, "https://telegra.ph/story-1")
        return ArticleContinuation.find(doc, doc.selectFirst("article"), "https://telegra.ph/story-1", title)
    }
    @Test fun explicitNextMetadataAndRelativeUrl() {
        assertEquals("https://telegra.ph/story-2", find("<link rel=next href='/story-2#chapter'><article>Текст</article>"))
        assertNull(find("<link rel=next href='https://other.example/story-2'>"))
        assertNull(find("<link rel=next href='http://telegra.ph/story-2'>"))
        assertNull(find("<link rel=next href='/story-1#start'>"))
    }
    @Test fun followingNumberAtEndOnly() {
        assertEquals("https://telegra.ph/story-2", find("<article><p>Конец первой части.</p><p><a href='/story-2'>Часть 2</a></p></article>"))
        assertNull(find("<article><p><a href='/story-2'>Часть 2</a></p></article>", "Рассказ · Часть 3"))
        val toc = "<article><p><a href='/story-2'>Часть 2</a></p>" + "<p>Длинный текст.</p>".repeat(10) + "</article>"
        assertNull(find(toc))
    }
    @Test fun noRecommendationsAndNoAmbiguousChoices() {
        assertNull(find("<article><p><a href='/other'>Вам также понравится</a></p></article>"))
        assertNull(find("<article><p><a href='/a'>Продолжение</a><a href='/b'>Далее</a></p></article>"))
        assertEquals("https://telegra.ph/story-2", find("<article><p><a href='/story-2'>→ Читать дальше</a></p></article>"))
    }
    @Test fun encodedPathsAreNotChanged() {
        assertEquals("https://telegra.ph/a%2Fb", find("<link rel=next href='/a%2Fb'>"))
        assertEquals("https://telegra.ph/a", ArticleContinuation.canonicalUrl("https://telegra.ph/a#fragment"))
    }
    @Test fun fullSeriesTitleRecognizesNextButNotOtherArticles() {
        assertEquals("https://telegra.ph/story-2", find("<article><p><a href='/story-2'>Рассказ · Часть 2</a></p></article>"))
        assertNull(find("<article><p><a href='/story-2'>Другой рассказ · Часть 2</a></p></article>"))
        assertNull(find("<article><div class='related'><p><a href='/story-2'>Рассказ · Часть 2</a></p></div></article>"))
    }
    @Test fun cancelledSessionCannotCompleteNewDownloads() {
        var cancelled = false
        val first = ArticleSession.begin { cancelled = true }
        val second = ArticleSession.begin {}
        assertTrue(cancelled); assertFalse(ArticleSession.current(first)); assertTrue(ArticleSession.current(second))
        ArticleSession.complete(first); assertTrue(ArticleSession.pending)
        ArticleSession.complete(second); assertFalse(ArticleSession.pending)
        ArticleSession.cancel(); assertFalse(ArticleSession.current(second))
    }
}
