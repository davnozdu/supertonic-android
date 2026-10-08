package com.brahmadeo.supertonic.tts.books

/** Sentence fingerprints of the `.mytts-book` format v1 (docs/multivoice-books.md). Must match
 * tools/characters/book_characters.py bit for bit: Moon+ sends only text, so a book is recognised by
 * the first 48 letters of its sentences. FNV-1a costs ~1 µs per sentence (measured on the whole «Идиот»). */
object BookFingerprint {
    const val ALGORITHM = "fnv1a64-48"
    const val LETTERS = 48
    const val MIN_LETTERS = 24
    // Python's \s includes NBSP and other Unicode spaces; Java's \s does not.
    private val sentences = Regex("(?<=[.!?…])[\\s\\p{Z}\\u0085\\u001c-\\u001f]+")
    private const val OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val PRIME = 0x100000001b3L

    /** Lowercase letters and digits only (ё → е, no stress marks), at most [limit]. */
    fun letters(text: String, limit: Int = Int.MAX_VALUE): String {
        val out = StringBuilder(minOf(limit, text.length))
        for (ch in text) {
            val c = ch.lowercaseChar().let { if (it == 'ё') 'е' else it }
            if (c in 'а'..'я' || c in 'a'..'z' || c in '0'..'9') {
                out.append(c)
                if (out.length == limit) break
            }
        }
        return out.toString()
    }

    fun hash(key: String): Long {
        var h = OFFSET
        for (c in key) { h = h xor c.code.toLong(); h *= PRIME }
        return h
    }

    /** Fingerprints of the sentences of [text] that have at least [MIN_LETTERS] letters. */
    fun sentences(text: String): List<Long> = sentences.split(text).mapNotNull { sentence ->
        letters(sentence, LETTERS).takeIf { it.length >= MIN_LETTERS }?.let(::hash)
    }

    fun hex(h: Long): String = java.lang.Long.toUnsignedString(h, 16).padStart(16, '0')
    fun parse(hex: String): Long = java.lang.Long.parseUnsignedLong(hex, 16)
}
