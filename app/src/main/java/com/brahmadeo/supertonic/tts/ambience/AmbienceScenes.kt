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
    private class State(var before: List<String> = emptyList(), var scene: AmbiencePlayer.Scene? = null, val matcher: Any = Any(),
                        var section: Pair<Long, String>? = null, var mood: AmbiencePlayer.Scene? = null)

    /** What an utterance brings: its scene (null: music), whether a new section of the book starts with it (and that
     * book's transition sound), the event sounds each with the word it starts at (an utterance may hold several), and
     * its length in words (the delay of an event follows the voice's own pace). */
    data class Moment(val file: java.io.File, val word: Int)
    data class Tag(val scene: AmbiencePlayer.Scene?, val chapter: Boolean, val chapterSound: java.io.File? = null,
                   val events: List<Moment> = emptyList(), val words: Int = 0,
                   /** «Музыка по настроению»: the music of the passage (or chapter), played where there is no scene. */
                   val mood: AmbiencePlayer.Scene? = null)
    private val states = java.util.WeakHashMap<Any, State>()

    /** The scene for [text] of [owner], or null (no prepared book here, no location, no sounds: the music plays). */
    fun locate(context: Context, owner: Any, text: String?, flush: Boolean = false): AmbiencePlayer.Scene? = tag(context, owner, text, flush).scene

    fun tag(context: Context, owner: Any, text: String?, flush: Boolean = false): Tag {
        val prefs = context.getSharedPreferences("SupertonicPrefs", 0)
        val sounds = AmbienceSettings.enabled(prefs)
        val withEvents = AmbienceSettings.events(prefs)
        val withMood = AmbienceSettings.moodMusic(prefs)
        if (text.isNullOrBlank() || !(sounds || withEvents || withMood || AmbienceSettings.chapters(prefs))) return Tag(null, false)
        val state = synchronized(states) { states.getOrPut(owner) { State() } }
        if (flush) { state.before = emptyList(); state.section = null } // the reader jumped: the previous words are elsewhere
        var chapter = false
        var chapterSound: java.io.File? = null
        val moments = ArrayList<Moment>()
        var wordCount = 0
        var mood: AmbiencePlayer.Scene? = null
        val scene = runCatching {
            val words = ReplyIndex.words(text)
            wordCount = words.size
            val before = state.before
            state.before = (before + words).takeLast(ReplyIndex.WINDOW - 1)
            val position = BookMatcher.feed(context, state.matcher, text) ?: return@runCatching null.also { state.scene = null; trace(context, "no book") }
            // A new section of the same book (not the first one recognised, not a jump).
            val section = position.book to position.section
            chapter = state.section != null && state.section != section && state.section?.first == position.book
            state.section = section
            val scenes = BookLibrary.scenes(context, position.book)
            val sha = BookLibrary.contentSha(context, position.book) ?: return@runCatching null
            if (chapter) chapterSound = BookFiles.event(context, sha, com.brahmadeo.supertonic.tts.books.EventIndex.CHAPTER_ID)
            if (withEvents) for (hit in scenes.events.hitAll(text, before)) {
                val file = BookFiles.event(context, sha, com.brahmadeo.supertonic.tts.books.EventIndex.fileKey(hit.event)) ?: continue
                moments += Moment(file, hit.word)
                trace(context, "event ${hit.event.id} at word ${hit.word} of ${words.size}")
            }
            if (withMood) {
                val found = if (AmbienceSettings.moodByChapter(prefs)) scenes.moods.ofSection(position.section) else scenes.moods.find(text, before)
                if (found != null) {
                    val files = BookFiles.sounds(context, sha, found.id)
                    state.mood = AmbiencePlayer.Scene("music/$sha/${found.id}", found.name, files).takeIf { files.isNotEmpty() }
                }
                mood = state.mood // a piece without a known word keeps the current mood
            }
            if (!sounds) return@runCatching null
            if (scenes.locations.isEmpty()) return@runCatching null.also { trace(context, "book=${position.book} has no locations") }
            val location = when (val found = scenes.find(text, before)) {
                is com.brahmadeo.supertonic.tts.books.SceneIndex.At -> found.location
                com.brahmadeo.supertonic.tts.books.SceneIndex.Outside -> { trace(context, "book=${position.book} outside"); state.scene = null; return@runCatching null }
                com.brahmadeo.supertonic.tts.books.SceneIndex.Unknown -> { trace(context, "book=${position.book} unknown keep=${state.scene?.key}"); return@runCatching state.scene }
            }
            // The first N variants (the setting may have been lowered after more were made).
            val n = AmbienceBook.variants(context, scenes.locations.size)
            val files = BookFiles.sounds(context, sha, location.id).filter { it.name.substringAfter('_').substringBefore('.').toIntOrNull()?.let { k -> k < n } == true }
            trace(context, "book=${position.book} at=${location.id} files=${files.size}")
            AmbiencePlayer.Scene("$sha/${location.id}", location.name, files).takeIf { files.isNotEmpty() }.also { state.scene = it }
        }.getOrElse { trace(context, "error ${it.javaClass.simpleName}"); null }
        if (chapter) trace(context, "chapter starts")
        return Tag(scene, chapter, chapterSound, moments, wordCount, mood)
    }

    /** Last decisions (no book text), with verbose logs only (no flash writes while reading otherwise): in
     * cache/ambience-trace.txt, because OnePlus drops app logs after a while. */
    private val lines = ArrayDeque<String>()
    private var lastLine = ""
    fun trace(context: Context, line: String) {
        if (!com.brahmadeo.supertonic.tts.utils.DiagLog.verbose) return
        synchronized(lines) {
            // Every queued sentence tags its scene: a repeat of the last line is skipped, so the decisions stay visible.
            if (line == lastLine) return
            lastLine = line
            lines.addLast("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())} $line")
            while (lines.size > 600) lines.removeFirst()
            if (writePending) return
            writePending = true
        }
        // At most one write in 2 s (≤ ~40 KB, replaced, never grows): no flash write per sentence.
        traceHandler.postDelayed({
            val text = synchronized(lines) { writePending = false; lines.joinToString("\n") }
            runCatching { java.io.File(context.cacheDir, "ambience-trace.txt").writeText(text + "\n") }
        }, 2000)
    }
    private var writePending = false
    private val traceHandler by lazy { android.os.Handler(android.os.HandlerThread("ambience-trace").apply { start() }.looper) }

    fun forget(owner: Any) = synchronized(states) { states.remove(owner); Unit }
}
