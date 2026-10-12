package com.brahmadeo.supertonic.tts.foreign

/** Languages connected in «Мультиязычная озвучка», each with its own Android TTS engine ("" = the default one).
 * Stored as "en=com.google.android.tts;cs=" in SupertonicPrefs "foreign_languages". Without it the older settings
 * apply: one engine ("foreign_engine") for English and Czech, or only the language chosen in "foreign_language". */
object ForeignLanguages {
    data class Entry(val language: String, val engine: String)
    const val KEY = "foreign_languages"

    fun parse(stored: String?, legacyEngine: String, legacyLanguage: String): List<Entry> {
        if (stored != null) return stored.split(';').mapNotNull { item ->
            val language = item.substringBefore('=').trim().lowercase()
            if (language.isEmpty() || language == "ru") null else Entry(language, item.substringAfter('=', "").trim())
        }.distinctBy { it.language }
        val languages = when (legacyLanguage) { "en" -> listOf("en"); "cs" -> listOf("cs"); else -> listOf("en", "cs") }
        return languages.map { Entry(it, legacyEngine) }
    }

    fun serialize(entries: List<Entry>) = entries.joinToString(";") { "${it.language}=${it.engine}" }

    /** Language of a Latin (or other non-Cyrillic) fragment: the reader's own tag when it names a connected language,
     * a Czech letter or word, the detector's best connected guess, otherwise the first connected language. */
    fun pick(text: String, requested: String, connected: List<String>, detected: List<String> = emptyList()): String? {
        if (connected.isEmpty()) return null
        val req = requested.lowercase().substringBefore('-').substringBefore('_').let {
            when (it) { "ces", "cze" -> "cs"; "eng" -> "en"; "deu", "ger" -> "de"; "fra", "fre" -> "fr"; "spa" -> "es"; else -> it }
        }
        if (req in connected) return req
        if (connected.size == 1) return connected.single()
        if ("cs" in connected && ForeignText.looksCzech(text)) return "cs"
        detected.firstOrNull { it in connected }?.let { return it }
        return connected.first()
    }
}
