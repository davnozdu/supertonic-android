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

    /** Sound variants per scene (2–8, default 3): steady background, then one with each of the place's events, again
     * with new seeds; played in a random order. */
    const val VARIANTS = "ambience_variants"
    const val DEFAULT_VARIANTS = 3
    /** «Сглаживать резкие звуки»: a compressor keeps the events (a whistle) close to the background level. */
    const val TAME = "ambience_tame"

    /** «Дыхание звука»: a new scene is a little louder for a moment, the background dips under speech. Off by default. */
    const val BREATHE = "ambience_breathe"
    /** «Переходы между главами»: the background fades out and back at a new section. Off by default. */
    const val CHAPTERS = "ambience_chapters"

    fun breathe(prefs: SharedPreferences) = prefs.getBoolean(BREATHE, false)
    fun chapters(prefs: SharedPreferences) = prefs.getBoolean(CHAPTERS, false)

    fun variants(prefs: SharedPreferences) = prefs.getInt(VARIANTS, DEFAULT_VARIANTS).coerceIn(2, 8)
    fun tame(prefs: SharedPreferences) = prefs.getBoolean(TAME, true)

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
