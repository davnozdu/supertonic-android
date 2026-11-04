package com.brahmadeo.supertonic.tts.tera

import android.content.Context

/** «Ритм голоса» для импортированных голосов Tera (идея azimutspb из TeraTTS-GUI, перенесена в форк TeraTTS 1.6.34).
 * Голос, сделанный для другой модели (.json Supertonic), сохраняет в Tera свой тембр (style_ttl), но его ритм
 * (style_dp) не подходит предсказателю длительностей Tera — слова пропускаются. Такому голосу можно отдать ритм
 * родного голоса Tera: тембр остаётся свой. Выбирается для каждого своего голоса, по умолчанию — его собственный. */
object TeraVoiceRhythm {
    private const val PREFS = "tera_voice_rhythm"
    /** Родные русские голоса Tera, у которых берётся ритм. */
    val DONORS = listOf("ru_m1", "ru_m5", "ru_f1", "ru_f2")

    fun donor(context: Context, voice: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(voice, "").orEmpty().takeIf { it in DONORS }.orEmpty()

    fun setDonor(context: Context, voice: String, donor: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (donor.isEmpty()) remove(voice) else putString(voice, donor)
        }.commit()
    }
}
