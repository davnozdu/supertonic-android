package com.brahmadeo.supertonic.tts.ambience

import android.content.SharedPreferences

/** Location sounds and background music can both be on: in a scene with a location sound it plays, everywhere else
 * (narration without a scene, places not recognised, books without locations) the music; never both at once
 * (BackgroundMusic switches between them). */
object AmbienceSettings {
    const val ENABLED = "ambience_enabled"
    const val VOLUME = "ambience_volume"
    const val DEFAULT_VOLUME = 5
    private const val MUSIC = "background_music_enabled"

    fun enabled(prefs: SharedPreferences) = prefs.getBoolean(ENABLED, false)
    fun volume(prefs: SharedPreferences) = prefs.getInt(VOLUME, DEFAULT_VOLUME).coerceIn(0, 100)

    fun setAmbience(prefs: SharedPreferences, on: Boolean) = prefs.edit().putBoolean(ENABLED, on).commit()

    /** For every place that switches the music on (switch, track choice, own MP3). */
    fun SharedPreferences.Editor.musicOn(): SharedPreferences.Editor = putBoolean(MUSIC, true)
}
