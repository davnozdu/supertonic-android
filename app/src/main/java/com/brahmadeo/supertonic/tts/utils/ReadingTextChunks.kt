package com.brahmadeo.supertonic.tts.utils

data class ReadingTextChunks(val sentences: List<String>, val paragraphStarts: List<Int>) {
    fun moveParagraph(current: Int, direction: Int): Int {
        val paragraph = paragraphStarts.indexOfLast { it <= current }.coerceAtLeast(0)
        return paragraphStarts.getOrNull((paragraph + direction).coerceIn(0, (paragraphStarts.size - 1).coerceAtLeast(0))) ?: 0
    }
    companion object {
        fun split(text: String, lang: String, preservePunctuation: Boolean): ReadingTextChunks {
            val normalizer = TextNormalizer()
            val sentences = mutableListOf<String>(); val starts = mutableListOf<Int>()
            for (paragraph in text.split(Regex("\\n\\s*\\n"))) {
                val parts = normalizer.splitIntoSentences(paragraph, lang, preservePunctuation)
                if (parts.isNotEmpty()) { starts.add(sentences.size); sentences.addAll(parts) }
            }
            return ReadingTextChunks(sentences, starts)
        }
    }
}
