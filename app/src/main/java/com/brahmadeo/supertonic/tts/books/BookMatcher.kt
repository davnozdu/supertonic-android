package com.brahmadeo.supertonic.tts.books

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Recognises a prepared book and its section in the text a reader sends (Moon+ gives no book id).
 * Another book is taken only after two fingerprints in a row agree on it (a stray coincidence never
 * switches); inside the current book a single hit moves the section. Eight chunks with fingerprints and
 * no hit drop the book, so an unprepared book is never read with another book's characters.
 * State is per TTS client; the result belongs to the text it was computed for (readers queue ahead). */
object BookMatcher {
    data class Position(val book: Long, val section: String)

    private const val CONFIRM = 2
    private const val MISSES_TO_DROP = 8

    private class State {
        var current: Position? = null
        var candidate: Long? = null
        var streak = 0
        var misses = 0
    }

    private val states = java.util.WeakHashMap<Any, State>()
    private val last = MutableStateFlow<Position?>(null)
    /** For the books screen: what the last reading recognised. */
    val recognised = last.asStateFlow()

    fun feed(context: Context, caller: Any, text: String): Position? {
        val hashes = BookFingerprint.sentences(text)
        val hits = if (hashes.isEmpty()) emptyList() else runCatching { BookLibrary.lookup(context, hashes) }.getOrElse {
            com.brahmadeo.supertonic.tts.utils.DiagLog.i("Books", "Lookup failed: ${it.javaClass.simpleName}")
            emptyList()
        }
        synchronized(states) {
            val s = states.getOrPut(caller) { State() }
            val before = s.current
            if (hashes.isNotEmpty() && hits.isEmpty()) {
                if (s.current != null && ++s.misses >= MISSES_TO_DROP) { s.current = null; s.candidate = null; s.streak = 0 }
            }
            for (hit in hits) {
                s.misses = 0
                val current = s.current
                if (current != null && current.book == hit.book) {
                    s.current = Position(hit.book, hit.section)
                    s.candidate = null; s.streak = 0
                    continue
                }
                if (s.candidate == hit.book) s.streak++ else { s.candidate = hit.book; s.streak = 1 }
                if (s.streak >= CONFIRM) {
                    s.current = Position(hit.book, hit.section)
                    s.candidate = null; s.streak = 0
                }
            }
            if (s.current != before) {
                com.brahmadeo.supertonic.tts.utils.DiagLog.i("Books", "Reading position book=${s.current?.book} section=${s.current?.section} (was ${before?.book}/${before?.section})")
                last.value = s.current
            }
            return s.current
        }
    }

    fun forget(caller: Any) = synchronized(states) { states.remove(caller); Unit }
    fun forgetAll() = synchronized(states) { states.clear(); last.value = null }
}
