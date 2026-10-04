package com.brahmadeo.supertonic.tts.article

import org.junit.Assert.*
import org.junit.Test

class SharedArticleInputTest {
    @Test fun browserCaptionDoesNotHideClipDataUrl() {
        assertEquals("https://meduza.io/feature/story", SharedArticleInput.choose(listOf("Заголовок статьи", "https://meduza.io/feature/story")))
        assertEquals("https://example.org/article", SharedArticleInput.choose(listOf("", "Статья\nhttps://example.org/article")))
    }
    @Test fun selectedTextAndEmptyShareAreHandledSeparately() {
        val selected = "Выделенный текст. ".repeat(300)
        assertEquals(selected, SharedArticleInput.choose(listOf(selected, "https://example.org")))
        assertEquals("Просто текст", SharedArticleInput.choose(listOf("Просто текст")))
        assertNull(SharedArticleInput.choose(listOf("", " ")))
    }
}
