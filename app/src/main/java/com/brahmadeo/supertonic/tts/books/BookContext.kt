package com.brahmadeo.supertonic.tts.books

/** Characters of the section being read and their voices, as given to the role request. */
/** [roles]: the book's own narrator / «прочие» voices when set for this book (otherwise the global ones apply). */
/** [firstPerson]: first-person narration chosen for this book; null — the global multi-voice setting applies. */
data class BookContext(val book: Long, val section: String, val cast: BookPackage.Cast, val voices: Map<String, String>,
                       val roles: Map<com.brahmadeo.supertonic.tts.llm.VoiceRole, String> = emptyMap(), val firstPerson: Boolean? = null) {
    /** Role caches must not mix a book-aware answer with a plain one. */
    val key: String get() = "$book/$section/${voices.hashCode()}/${roles.hashCode()}/$firstPerson"

    /** Voice of a part: its character's, else the book's own voice of the role, else none (global role voice). */
    fun voiceOf(role: com.brahmadeo.supertonic.tts.llm.VoiceRole, character: String?): String? =
        character?.let { voices[it] } ?: roles[role]
}
