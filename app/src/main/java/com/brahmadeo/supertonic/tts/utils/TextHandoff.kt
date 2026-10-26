package com.brahmadeo.supertonic.tts.utils

/**
 * A long text handed from one screen to another. Inside an Intent it goes through the binder,
 * whose 1 MB limit covers everything in flight: a whole chapter ("Unreasonably large binder
 * buffer") killed the app on some phones (MIUI, Android 10). A long text stays in memory and
 * the Intent carries a mark. Memory only, like the rest of the reading state: after the process
 * was killed the mark resolves to nothing. Idea from the TeraTTS fork 1.6.9 (TextHandoff.kt).
 */
object TextHandoff {
    private const val MARK = "\u0000mytts-handoff:"
    private const val LIMIT = 20_000
    private val held = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** What to put into the Intent instead of [text]; [slot] keeps different hand-offs apart. */
    fun wrap(text: String, slot: String = "playback"): String {
        if (text.length < LIMIT) return text
        held[slot] = text
        return MARK + slot
    }

    /** The text an Intent extra stands for (the extra itself if it is an ordinary text). */
    fun unwrap(extra: String?): String? =
        if (extra != null && extra.startsWith(MARK)) held[extra.removePrefix(MARK)] else extra
}
