package com.brahmadeo.supertonic.tts.books

/** Voice genders of the bundled models and the character → voice assignment (pure, unit-tested). Main
 * characters (speak in remarks or are often mentioned) get the voice hinted in the file when it exists in
 * the installed model, otherwise a free voice of their gender; when voices run out — none (the role voice,
 * that is the «прочие»). Voices reserved for the narrator and the «прочие» are never given out. */
object BookVoiceAssign {
    private const val MIN_SPEAKER = 2
    private const val MIN_MENTIONS = 30
    private val silero = mapOf(
        "aidar" to "m", "eugene" to "m", "kseniya" to "f", "baya" to "f", "xenia" to "f",
        "ru_alexandr" to "m", "ru_bogdan" to "m", "ru_dmitriy" to "m", "ru_gamat" to "m", "ru_igor" to "m",
        "ru_marat" to "m", "ru_roman" to "m", "ru_safarhuja" to "m", "ru_eduard" to "m", "ru_miyau" to "m",
        "ru_onaoy" to "m", "ru_sibday" to "m",
        "ru_aigul" to "f", "ru_albina" to "f", "ru_alfia" to "f", "ru_alfia2" to "f", "ru_ekaterina" to "f",
        "ru_vika" to "f", "ru_karina" to "f", "ru_kejilgan" to "f", "ru_kermen" to "f", "ru_nurgul" to "f",
        "ru_oksana" to "f", "ru_ramilia" to "f", "ru_saida" to "f", "ru_zara" to "f", "ru_zhadyra" to "f",
        "ru_zhazira" to "f", "ru_zinaida" to "f",
        // Kokoro and Shtorm PocketTTS
        "dima" to "m", "masha" to "f", "sveta" to "f",
        "marius" to "m", "jean" to "m", "javert" to "m", "alba" to "f", "eponine" to "f", "fantine" to "f", "cosette" to "f",
    )

    /** "m", "f" or null when unknown (such a voice is never given to a character). */
    fun gender(voice: String): String? = silero[voice] ?: when {
        Regex("(^|_)m\\d").containsMatchIn(voice) -> "m"   // Tera: ru_m5
        Regex("(^|_)f\\d").containsMatchIn(voice) -> "f"
        else -> null
    }

    fun assign(cast: BookPackage.Cast, available: List<String>, reserved: Set<String>): Map<String, String> {
        val pool = available.filter { it !in reserved && gender(it) != null }
        val taken = mutableSetOf<String>()
        val out = linkedMapOf<String, String>()
        val main = cast.characters.filter { it.gender in listOf("m", "f") && (it.speaker >= MIN_SPEAKER || it.mentions >= MIN_MENTIONS) }
            .sortedWith(compareBy({ -it.speaker }, { -it.mentions }, { it.id }))
        // Hints first: a hint may be shared on purpose (characters who never meet in the text). Free voices
        // are given only afterwards, so they never take a voice another character's hint points to.
        for (ch in main) {
            val hint = ch.voiceHint?.takeIf { it in pool && gender(it) == ch.gender } ?: continue
            out[ch.id] = hint
            taken += hint
        }
        for (ch in main) {
            if (ch.id in out) continue
            val voice = pool.firstOrNull { it !in taken && gender(it) == ch.gender } ?: continue
            out[ch.id] = voice
            taken += voice
        }
        return out
    }
}
