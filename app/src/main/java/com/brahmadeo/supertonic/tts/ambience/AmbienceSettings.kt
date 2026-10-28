package com.brahmadeo.supertonic.tts.ambience

import android.content.SharedPreferences

/** Location sounds and background music can both be on: in a scene with a location sound it plays, everywhere else
 * (narration without a scene, places not recognised, books without locations) the music; never both at once
 * (BackgroundMusic switches between them). */
object AmbienceSettings {
    const val ENABLED = "ambience_enabled"
    const val VOLUME = "ambience_volume"
    const val DEFAULT_VOLUME = 30
    private const val MUSIC = "background_music_enabled"

    fun enabled(prefs: SharedPreferences) = prefs.getBoolean(ENABLED, false)
    /** Since 5.0.6-beta42 the sounds have +10 dB of headroom: a volume set before (100 % was just audible) is moved
     * once to the new default, so the update does not make them 10 dB louder. */
    fun volume(prefs: SharedPreferences): Int {
        if (!prefs.getBoolean("ambience_volume_v2", false)) prefs.edit().remove(VOLUME).putBoolean("ambience_volume_v2", true).apply()
        return prefs.getInt(VOLUME, DEFAULT_VOLUME).coerceIn(0, 100)
    }

    fun setAmbience(prefs: SharedPreferences, on: Boolean) = prefs.edit().putBoolean(ENABLED, on).commit()

    /** For every place that switches the music on (switch, track choice, own MP3). */
    fun SharedPreferences.Editor.musicOn(): SharedPreferences.Editor = putBoolean(MUSIC, true)
}
