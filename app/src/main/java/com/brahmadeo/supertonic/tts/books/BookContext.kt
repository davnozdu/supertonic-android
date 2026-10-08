package com.brahmadeo.supertonic.tts.books

/** Characters of the section being read and their voices, as given to the role request. */
data class BookContext(val book: Long, val section: String, val cast: BookPackage.Cast, val voices: Map<String, String>) {
    /** Role caches must not mix a book-aware answer with a plain one. */
    val key: String get() = "$book/$section/${voices.hashCode()}"
}
