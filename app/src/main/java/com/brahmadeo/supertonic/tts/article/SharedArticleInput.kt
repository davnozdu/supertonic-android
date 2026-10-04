package com.brahmadeo.supertonic.tts.article

object SharedArticleInput {
    fun choose(candidates: List<String>): String? {
        val first = candidates.firstOrNull { it.isNotBlank() } ?: return null
        // Selected long text is already the article. Short browser captions may keep the URL in ClipData.
        if (first.length > 4096) return first
        return candidates.firstNotNullOfOrNull { ArticleExtractor.sharedUrl(it) } ?: first
    }
}
