package com.brahmadeo.supertonic.tts.article

import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class ArticleStructuredDataTest {
    private val body = "Статья из кода страницы. Первая фраза сохраняется полностью, без рекламы и меню.\n\nВторой абзац — вопрос? Да! Последняя строка."
    @Test fun readsArticleInGraphWhenVisiblePageHasOnlyJavascriptShell() {
        val data = JSONObject().put("@type", "NewsArticle").put("articleBody", body).toString()
        val html = "<title>Статья?</title><script type='application/ld+json'>{\"@graph\":[$data]}</script><div id='app'>Загрузка</div>"
        val result = ArticleExtractor.extract(html, "https://example.org/a")
        assertEquals("Статья?\n\n$body", result.text)
    }
    @Test fun ignoresOtherTypesAndMalformedJson() {
        val data = JSONObject().put("@type", "Advertisement").put("articleBody", body).toString()
        assertNull(ArticleStructuredData.body(Jsoup.parse("<script type='application/ld+json'>$data</script>")))
        assertNull(ArticleStructuredData.body(Jsoup.parse("<script type='application/ld+json'>{malformed}</script>")))
    }
    @Test fun completeVisibleArticleWinsOverStructuredExcerpt() {
        val data = JSONObject().put("@type", "Article").put("articleBody", body).toString()
        val full = body + "\n\nПоследний абзац полной статьи, отсутствующий в метаданных."
        val html = "<title>Текст</title><script type='application/ld+json'>$data</script><article>" + full.split("\n\n").joinToString("") { "<p>$it</p>" } + "</article>"
        assertTrue(ArticleExtractor.extract(html, "https://example.org/a").text.endsWith("отсутствующий в метаданных."))
    }
}
