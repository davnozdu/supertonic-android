package com.brahmadeo.supertonic.tts.utils

import android.content.SharedPreferences

/** The shared punctuation switch and duration for Shtorm and Kokoro; natural model silence is retained. */
object SentencePauses {
    fun milliseconds(prefs: SharedPreferences): Int =
        if (prefs.getBoolean("tera_punctuation_pauses", true))
            prefs.getInt("tera_sentence_pause_ms", 420).coerceIn(0, 900) else 0
}
