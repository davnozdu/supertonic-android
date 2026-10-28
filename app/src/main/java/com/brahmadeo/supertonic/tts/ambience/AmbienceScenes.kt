package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import com.brahmadeo.supertonic.tts.books.BookFiles
import com.brahmadeo.supertonic.tts.books.BookLibrary
import com.brahmadeo.supertonic.tts.books.BookMatcher
import com.brahmadeo.supertonic.tts.books.ReplyIndex

/** The location of a reader's text: the prepared book (BookMatcher, with its own state per reader so the roles'
 * matching is not disturbed) and its [com.brahmadeo.supertonic.tts.books.SceneIndex]. A short piece without a hit
 * keeps the current scene (one line of dialogue must not switch to the music); a longer one leaves it. */
internal object AmbienceScenes {
    private const val LEAVE_WORDS = 8
    /** [matcher]: this reader's key in BookMatcher (lives as long as the reader's entry here). */
    private class State(var before: List<String> = emptyList(), var scene: AmbiencePlayer.Scene? = null, val matcher: Any = Any())
    private val states = java.util.WeakHashMap<Any, State>()

    /** The scene for [text] of [owner], or null (no prepared book here, no location, no sounds: the music plays). */
    fun locate(context: Context, owner: Any, text: String?): AmbiencePlayer.Scene? {
        if (text.isNullOrBlank() || !AmbienceSettings.enabled(context.getSharedPreferences("SupertonicPrefs", 0))) return null
        val state = synchronized(states) { states.getOrPut(owner) { State() } }
        return runCatching {
            val words = ReplyIndex.words(text)
            val before = state.before
            state.before = (before + words).takeLast(ReplyIndex.WINDOW - 1)
            val position = BookMatcher.feed(context, state.matcher, text) ?: return@runCatching null.also { state.scene = null }
            val scenes = BookLibrary.scenes(context, position.book)
            if (scenes.locations.isEmpty()) return@runCatching null
            val location = scenes.resolve(text, before)
            if (location == null) {
                if (words.size < LEAVE_WORDS) return@runCatching state.scene
                state.scene = null; return@runCatching null
            }
            val sha = BookLibrary.contentSha(context, position.book) ?: return@runCatching null
            val files = BookFiles.sounds(context, sha, location.id)
            AmbiencePlayer.Scene("$sha/${location.id}", location.name, files).takeIf { files.isNotEmpty() }.also { state.scene = it }
        }.getOrNull()
    }

    fun forget(owner: Any) = synchronized(states) { states.remove(owner); Unit }
}
