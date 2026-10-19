package com.brahmadeo.supertonic.tts.utils

import android.content.Context

/** Sources of stress marks and ё, each switched separately in «Ударения и буква ё». By default only Silero Stress
 * (its accentor, homograph model and safe ё table). The LLM's own stress and ё are LlmConfig.stress / restoreYo. */
object StressOptions {
    const val SILERO = "local_russian_stress"
    const val RUACCENT = "stress_ruaccent"
    const val NAMES = "stress_names"
    const val ARBITER = "stress_arbiter"
    const val YO_SILERO = "yo_silero"
    val keys = setOf(SILERO, RUACCENT, NAMES, ARBITER, YO_SILERO)
    private const val MIGRATED = "stress_options_v1"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("SupertonicPrefs", 0)
    fun silero(ctx: Context) = prefs(ctx).getBoolean(SILERO, true)
    /** RUAccent's full dictionary votes with Silero (179 MB, downloaded once over an unmetered network). */
    fun ruaccent(ctx: Context) = prefs(ctx).getBoolean(RUACCENT, false)
    /** Names dictionary (Wiktionary): stress and ё of capitalised names. */
    fun names(ctx: Context) = prefs(ctx).getBoolean(NAMES, false)
    /** The cloud LLM decides the dictionaries' disagreements and homographs. */
    fun arbiter(ctx: Context) = prefs(ctx).getBoolean(ARBITER, false)
    /** ё from Silero's verified table (черный -> чёрный) and its accentor exceptions. */
    fun yoSilero(ctx: Context) = prefs(ctx).getBoolean(YO_SILERO, true)
    /** At least one dictionary marks stress. */
    fun dictionaries(ctx: Context) = silero(ctx) || ruaccent(ctx)

    /** Once: the default becomes Silero Stress alone, also for an installation that had the LLM and the vote on. */
    fun migrate(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(MIGRATED, false)) return
        ctx.applicationContext.getSharedPreferences("llm_settings", 0).edit()
            .putBoolean("stress", false).putBoolean("restore_yo", false).apply()
        p.edit().putBoolean(SILERO, true).putBoolean(RUACCENT, false).putBoolean(NAMES, false)
            .putBoolean(ARBITER, false).putBoolean(YO_SILERO, true).putBoolean(MIGRATED, true).apply()
    }
}
