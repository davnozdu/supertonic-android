package com.brahmadeo.supertonic.tts.ambience

import android.content.SharedPreferences

/** Location ambience and background music never play together: turning one on turns the other off. */
object AmbienceSettings {
    const val ENABLED = "ambience_enabled"
    const val VOLUME = "ambience_volume"
    const val DEFAULT_VOLUME = 5
    private const val MUSIC = "background_music_enabled"

    fun enabled(prefs: SharedPreferences) = prefs.getBoolean(ENABLED, false)
    fun volume(prefs: SharedPreferences) = prefs.getInt(VOLUME, DEFAULT_VOLUME).coerceIn(0, 100)

    fun setAmbience(prefs: SharedPreferences, on: Boolean) =
        prefs.edit().putBoolean(ENABLED, on).apply { if (on) putBoolean(MUSIC, false) }.commit()

    /** For every place that switches the music on (switch, track choice, own MP3). */
    fun SharedPreferences.Editor.musicOn(): SharedPreferences.Editor = putBoolean(MUSIC, true).putBoolean(ENABLED, false)
}
