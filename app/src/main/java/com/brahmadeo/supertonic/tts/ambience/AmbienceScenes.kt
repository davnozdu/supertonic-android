package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import com.brahmadeo.supertonic.tts.books.BookFiles
import com.brahmadeo.supertonic.tts.books.BookLibrary
import com.brahmadeo.supertonic.tts.books.BookMatcher
import com.brahmadeo.supertonic.tts.books.ReplyIndex

/** The location of a reader's text: the prepared book (BookMatcher, with its own state per reader so the roles'
 * matching is not disturbed) and its [com.brahmadeo.supertonic.tts.books.SceneIndex]. Text marked outside any scene
 * leaves it (the music plays); a piece without a hit keeps the current sound. */
internal object AmbienceScenes {
    /** [matcher]: this reader's key in BookMatcher (lives as long as the reader's entry here). */
    private class State(var before: List<String> = emptyList(), var scene: AmbiencePlayer.Scene? = null, val matcher: Any = Any())
    private val states = java.util.WeakHashMap<Any, State>()

    /** The scene for [text] of [owner], or null (no prepared book here, no location, no sounds: the music plays). */
    fun locate(context: Context, owner: Any, text: String?, flush: Boolean = false): AmbiencePlayer.Scene? {
        if (text.isNullOrBlank() || !AmbienceSettings.enabled(context.getSharedPreferences("SupertonicPrefs", 0))) return null
        val state = synchronized(states) { states.getOrPut(owner) { State() } }
        if (flush) state.before = emptyList() // the reader jumped: the previous words are elsewhere
        return runCatching {
            val words = ReplyIndex.words(text)
            val before = state.before
            state.before = (before + words).takeLast(ReplyIndex.WINDOW - 1)
            val position = BookMatcher.feed(context, state.matcher, text) ?: return@runCatching null.also { state.scene = null; trace(context, "no book") }
            val scenes = BookLibrary.scenes(context, position.book)
            if (scenes.locations.isEmpty()) return@runCatching null.also { trace(context, "book=${position.book} has no locations") }
            val location = when (val found = scenes.find(text, before)) {
                is com.brahmadeo.supertonic.tts.books.SceneIndex.At -> found.location
                com.brahmadeo.supertonic.tts.books.SceneIndex.Outside -> { trace(context, "book=${position.book} outside"); state.scene = null; return@runCatching null }
                com.brahmadeo.supertonic.tts.books.SceneIndex.Unknown -> { trace(context, "book=${position.book} unknown keep=${state.scene?.key}"); return@runCatching state.scene }
            }
            val sha = BookLibrary.contentSha(context, position.book) ?: return@runCatching null
            // The first N variants (the setting may have been lowered after more were made).
            val n = AmbienceBook.variants(context, scenes.locations.size)
            val files = BookFiles.sounds(context, sha, location.id).filter { it.name.substringAfter('_').substringBefore('.').toIntOrNull()?.let { k -> k < n } == true }
            trace(context, "book=${position.book} at=${location.id} files=${files.size}")
            AmbiencePlayer.Scene("$sha/${location.id}", location.name, files).takeIf { files.isNotEmpty() }.also { state.scene = it }
        }.getOrElse { trace(context, "error ${it.javaClass.simpleName}"); null }
    }

    /** Last decisions (no book text), with verbose logs only (no flash writes while reading otherwise): in
     * cache/ambience-trace.txt, because OnePlus drops app logs after a while. */
    private val lines = ArrayDeque<String>()
    fun trace(context: Context, line: String) {
        if (!com.brahmadeo.supertonic.tts.utils.DiagLog.verbose) return
        val text = synchronized(lines) {
            lines.addLast("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())} $line")
            while (lines.size > 200) lines.removeFirst()
            lines.joinToString("\n")
        }
        runCatching { java.io.File(context.cacheDir, "ambience-trace.txt").writeText(text + "\n") }
    }

    fun forget(owner: Any) = synchronized(states) { states.remove(owner); Unit }
}
