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

    /** Texts in a row with sentences and no hit: an unknown book is being read. */
    private const val UNKNOWN_AFTER = 6
    @Volatile private var misses = 0
    @Volatile private var dueCheckedAt = 0L
    /** A reading session ends after a break this long; an unknown book is looked for once per session (a book read
     * from elsewhere would otherwise list the folder again and again). */
    private const val SESSION_GAP_MS = 10 * 60 * 1000L
    @Volatile private var heardAt = 0L
    @Volatile private var unknownChecked = false

    private fun feed(ctx: Context, caller: Int, text: String) {
        if (!enabled(ctx)) return
        val now = System.currentTimeMillis()
        if (now - heardAt > SESSION_GAP_MS) unknownChecked = false
        heardAt = now
        if (now - dueCheckedAt > 60_000) { dueCheckedAt = now; SmartFolder.scanIfDue(ctx) }
        val hashes = BookFingerprint.sentences(text)
        if (hashes.isEmpty()) return
        val hits = if (SmartLibrary.isEmpty(ctx)) emptyList() else SmartLibrary.lookup(ctx, hashes)
        if (hits.isEmpty()) {
            if (++misses >= UNKNOWN_AFTER && !unknownChecked) { misses = 0; unknownChecked = true; SmartFolder.scanForUnknown(ctx) }
            return
        }
        misses = 0
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
            val newBook = mutable.value?.key != current.key
            mutable.value = current
            if (newBook) com.brahmadeo.supertonic.tts.service.ReadingControls.refresh(ctx)
            save(ctx, current)
        }
        // Reading is going on: the chapters already read may get their summaries now (rarely, see SmartPrefetch).
        if (current != null) SmartPrefetch.reading(ctx, current)
    }

    private fun save(ctx: Context, p: Position) {
        val section = SmartLibrary.text(ctx, p.key)?.sectionOf(p.paragraph) ?: -1
        val now = System.currentTimeMillis()
        if (savedSection == p.key to section && now - savedAt < SAVE_EVERY_MS) return
        savedSection = p.key to section; savedAt = now
        ctx.getSharedPreferences("smart_book", Context.MODE_PRIVATE).edit()
            .putString("key", p.key).putInt("paragraph", p.paragraph).putLong("at", p.at).apply()
    }

    /** A place chosen by hand (the book was not recognised yet, or the listener means another chapter). */
    fun choose(context: Context, p: Position) {
        val ctx = context.applicationContext
        mutable.value = p
        com.brahmadeo.supertonic.tts.service.ReadingControls.refresh(ctx)
        executor.execute { runCatching { savedAt = 0; save(ctx, p) } }
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
