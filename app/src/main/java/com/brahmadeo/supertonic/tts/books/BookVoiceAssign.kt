package com.brahmadeo.supertonic.tts.books

/** Voice genders of the bundled models and the voices of a book: narrator, main characters, «прочие» (pure,
 * unit-tested; see [assign]). */
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

    /** Voices of one set of characters: [characters] by id, [male]/[female] for the «прочие» (null — none left,
     * the global multi-voice voice is used). */
    data class Assignment(val characters: Map<String, String>, val male: String?, val female: String?)

    /** The narrator reads most of the text, so [author] (the user's narrator voice) is taken first and never given
     * to a character. Then main characters by gender: [manual] choices, hints from the file, free voices. The
     * «прочие» take what is left — one voice of each gender is kept for them (unless [ownMale]/[ownFemale] are set),
     * so they never sound like a main character. */
    fun assign(cast: BookPackage.Cast, available: List<String>, author: String, manual: Map<String, String> = emptyMap(),
               ownMale: String? = null, ownFemale: String? = null): Assignment {
        val pool = available.filter { it != author && it != ownMale && it != ownFemale && gender(it) != null }
        val spare = mapOf("m" to if (ownMale == null) 1 else 0, "f" to if (ownFemale == null) 1 else 0)
        val limit = listOf("m", "f").associateWith { g -> pool.count { gender(it) == g } - spare.getValue(g) }
        val used = mapOf("m" to linkedSetOf<String>(), "f" to linkedSetOf())
        val out = linkedMapOf<String, String>()
        fun fits(voice: String, g: String) = voice in used.getValue(g) || used.getValue(g).size < limit.getValue(g)
        for (ch in cast.characters) manual[ch.id]?.takeIf { it in available }?.let { voice ->
            out[ch.id] = voice
            gender(voice)?.let { used.getValue(it) += voice }
        }
        val main = cast.characters.filter { it.id !in out && it.gender in listOf("m", "f") && (it.speaker >= MIN_SPEAKER || it.mentions >= MIN_MENTIONS) }
            .sortedWith(compareBy({ -it.speaker }, { -it.mentions }, { it.id }))
        // Hints first: a hint may be shared on purpose (characters who never meet in the text). Free voices
        // are given only afterwards, so they never take a voice another character's hint points to.
        for (ch in main) {
            val hint = ch.voiceHint?.takeIf { it in pool && gender(it) == ch.gender && fits(it, ch.gender) } ?: continue
            out[ch.id] = hint
            used.getValue(ch.gender) += hint
        }
        for (ch in main) {
            if (ch.id in out) continue
            val taken = used.values.flatten().toSet()
            val voice = pool.firstOrNull { it !in taken && gender(it) == ch.gender && fits(it, ch.gender) } ?: continue
            out[ch.id] = voice
            used.getValue(ch.gender) += voice
        }
        val taken = used.values.flatten().toSet()
        return Assignment(out, ownMale ?: pool.firstOrNull { gender(it) == "m" && it !in taken },
            ownFemale ?: pool.firstOrNull { gender(it) == "f" && it !in taken })
    }
}
