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

    /** «Звуки событий»: short sounds of what the text describes (a door slams). Off by default. */
    /** «База готовых звуков»: брать клипы из общей базы и дописывать в неё то, что сделано для книги. Включено
     * по умолчанию — это и есть «стоимость ноль»: готовый звук не генерируется заново. */
    const val SOUND_BANK = "sound_bank"
    fun soundBank(prefs: SharedPreferences) = prefs.getBoolean(SOUND_BANK, true)

    const val EVENTS = "ambience_events"
    fun events(prefs: SharedPreferences) = prefs.getBoolean(EVENTS, false)
    /** «Громкость звуков событий» (0–100 %, its own slider): by default a little above the location sounds. */
    const val EVENTS_VOLUME = "ambience_events_volume"
    const val DEFAULT_EVENTS_VOLUME = 40
    fun eventsVolume(prefs: SharedPreferences) = prefs.getInt(EVENTS_VOLUME, DEFAULT_EVENTS_VOLUME).coerceIn(0, 100)

    /** «Длина звука события», секунды. Клип в базе 8 с — это запас и затухание; приложение играет только первые
     * [eventSeconds] секунд с плавным затуханием, а новые звуки сразу делаются такой длины. */
    const val EVENT_SECONDS = "ambience_event_seconds"
    const val DEFAULT_EVENT_SECONDS = 3f
    fun eventSeconds(prefs: SharedPreferences) =
        prefs.getFloat(EVENT_SECONDS, DEFAULT_EVENT_SECONDS).coerceIn(1f, 8f)

    /** «Звук раньше текста», мс: событие начинает звучать раньше своих слов, чтобы слышаться вместе с действием
     * (0 — ровно со словом). Клип у базы короткий только по воспроизведению, начало важнее. */
    const val EVENT_LEAD = "ambience_event_lead"
    const val DEFAULT_EVENT_LEAD = 500
    fun eventLead(prefs: SharedPreferences) = prefs.getInt(EVENT_LEAD, DEFAULT_EVENT_LEAD).coerceIn(0, 2000)

    /** «Музыка по настроению»: outside scenes the music of the passage's mood, generated for the book. Off by default. */
    const val MOOD_MUSIC = "ambience_mood_music"
    /** "scene" (default) — the mood of each passage; "chapter" — one mood per chapter. */
    const val MOOD_SCOPE = "ambience_mood_scope"
    fun moodMusic(prefs: SharedPreferences) = prefs.getBoolean(MOOD_MUSIC, false)
    fun moodByChapter(prefs: SharedPreferences) = prefs.getString(MOOD_SCOPE, "scene") == "chapter"

    fun breathe(prefs: SharedPreferences) = prefs.getBoolean(BREATHE, false)
    fun chapters(prefs: SharedPreferences) = prefs.getBoolean(CHAPTERS, false)

    fun variants(prefs: SharedPreferences) = prefs.getInt(VARIANTS, DEFAULT_VARIANTS).coerceIn(2, 8)
    fun tame(prefs: SharedPreferences) = prefs.getBoolean(TAME, true)

    /** «Готовить NPU ночью»: an NPU graph not compiled yet is not compiled while making sounds (the CPU makes them);
     * a job compiles it when the phone charges and is idle. Off by default. */
    const val NPU_NIGHT = "ambience_npu_night"
    fun npuNight(prefs: SharedPreferences) = prefs.getBoolean(NPU_NIGHT, false)

    /** Генератор держит три графа открытыми всю подготовку книги: +650 МБ памяти, зато ~4 с с каждого клипа.
     * Включено по умолчанию — памяти на телефоне с избытком, а клипов на книгу десятки. */
    const val KEEP_MODELS = "ambience_keep_models"
    fun keepModels(prefs: SharedPreferences) = prefs.getBoolean(KEEP_MODELS, true)

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
