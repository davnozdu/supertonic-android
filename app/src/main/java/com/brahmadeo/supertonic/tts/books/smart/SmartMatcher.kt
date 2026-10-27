package com.brahmadeo.supertonic.tts.books.smart

import android.content.Context
import com.brahmadeo.supertonic.tts.books.BookFingerprint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** Where the book is being read, from the text the engine is synthesising now (not what a reader queues ahead).
 * Another book is taken after two fingerprints in a row agree on it; inside the current book one hit moves the place.
 * The last place is kept in memory and written to preferences only on a new book or section and at most every two
 * minutes, so reading does not write the flash for every paragraph. */
object SmartMatcher {
    const val PREF_ENABLED = "smart_book_enabled"
    data class Position(val key: String, val paragraph: Int, val at: Long)

    private const val CONFIRM = 2
    private const val SAVE_EVERY_MS = 2 * 60 * 1000L
    private class State { var candidate: String? = null; var streak = 0 }
    private val states = HashMap<Int, State>()
    private val mutable = MutableStateFlow<Position?>(null)
    val position = mutable.asStateFlow()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "SmartBook").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var savedAt = 0L
    @Volatile private var savedSection: Pair<String, Int>? = null

    fun enabled(context: Context) = context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, true)

    /** Called for every text the engine synthesises for a reader; the lookup runs off the synthesis thread. */
    fun heard(context: Context, caller: Int, text: String) {
        val ctx = context.applicationContext
        executor.execute { runCatching { feed(ctx, caller, text) } }
    }

    private fun feed(ctx: Context, caller: Int, text: String) {
        if (!enabled(ctx) || SmartLibrary.isEmpty(ctx)) return
        val hits = SmartLibrary.lookup(ctx, BookFingerprint.sentences(text))
        if (hits.isEmpty()) return
        val s = states.getOrPut(caller) { State() }
        var current = mutable.value ?: last(ctx)
        for (hit in hits) {
            if (current != null && current.key == hit.key) {
                current = Position(hit.key, hit.paragraph, System.currentTimeMillis()); s.candidate = null; s.streak = 0
                continue
            }
            if (s.candidate == hit.key) s.streak++ else { s.candidate = hit.key; s.streak = 1 }
            if (s.streak >= CONFIRM) { current = Position(hit.key, hit.paragraph, System.currentTimeMillis()); s.candidate = null; s.streak = 0 }
        }
        if (current != null && current != mutable.value) {
            mutable.value = current
            save(ctx, current)
        }
    }

    private fun save(ctx: Context, p: Position) {
        val section = SmartLibrary.text(ctx, p.key)?.sectionOf(p.paragraph) ?: -1
        val now = System.currentTimeMillis()
        if (savedSection == p.key to section && now - savedAt < SAVE_EVERY_MS) return
        savedSection = p.key to section; savedAt = now
        ctx.getSharedPreferences("smart_book", Context.MODE_PRIVATE).edit()
            .putString("key", p.key).putInt("paragraph", p.paragraph).putLong("at", p.at).apply()
    }

    /** The place read last: in memory, else as saved before the app was restarted. */
    fun last(context: Context): Position? = mutable.value ?: context.getSharedPreferences("smart_book", Context.MODE_PRIVATE).let { prefs ->
        val key = prefs.getString("key", null) ?: return null
        Position(key, prefs.getInt("paragraph", 0), prefs.getLong("at", 0))
    }

    /** Forget a removed book. */
    fun forget(context: Context, key: String) {
        if (mutable.value?.key == key) mutable.value = null
        val prefs = context.getSharedPreferences("smart_book", Context.MODE_PRIVATE)
        if (prefs.getString("key", null) == key) prefs.edit().clear().apply()
    }
}
