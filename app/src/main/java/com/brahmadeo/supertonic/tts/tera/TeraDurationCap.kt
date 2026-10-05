package com.brahmadeo.supertonic.tts.tera

/** The released duration predictor gives every utterance up to four vowels roughly the
 * same 0.9–1.2 s ("Да." == "Хорошо." == "Иди сюда!"), so a lone short reply is stretched
 * 2–3x. Long phrases run at 0.15–0.18 s per vowel and are never capped. Applied before
 * the speed divisor; the model still renders the whole word in the shorter latent. */
internal object TeraDurationCap {
    const val KEY = "tera_short_word_cap"
    private const val MAX_VOWELS = 4
    private const val BASE_SECONDS = .35f
    private const val PER_VOWEL_SECONDS = .20f
    private const val VOWELS = "аеёиоуыэюя"

    fun vowels(text: String) = text.count { it.lowercaseChar() in VOWELS }

    fun seconds(predicted: Float, text: String, enabled: Boolean = true): Float {
        val vowels = vowels(text)
        if (!enabled || vowels !in 1..MAX_VOWELS) return predicted
        return minOf(predicted, BASE_SECONDS + PER_VOWEL_SECONDS * vowels)
    }
}
