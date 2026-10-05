package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import java.io.File

/** Current reading state in RAM only. A text already read is not needed later, and
 * rewriting SharedPreferences XML (which held the whole text) after every sentence or
 * keystroke only wears flash. Lost with the process by design. */
object ReadingMemory {
    @Volatile var text = ""
    @Volatile var voicePath = ""
    @Volatile var lang = "en"
    @Volatile var speed = 1f
    @Volatile var steps = 5
    @Volatile var index = 0
    @Volatile var playing = false

    private val legacyKeys = listOf("last_text", "last_voice_path", "last_lang", "last_speed", "last_steps", "last_index", "is_playing")
    private val legacyFiles = listOf("playback_queue.json", "synthesis_history.json")
    @Volatile private var purged = false

    /** One-time cleanup of the former on-flash copies. */
    fun purgeLegacy(context: Context) {
        if (purged) return
        purged = true
        val prefs = context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        if (legacyKeys.any(prefs::contains)) prefs.edit().apply { legacyKeys.forEach { remove(it) } }.apply()
        legacyFiles.forEach { File(context.filesDir, it).delete() }
    }
}
