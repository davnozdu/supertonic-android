package com.brahmadeo.supertonic.tts.llm

/** Defaults use distinct voices where the installed pack has suitable alternatives.
 * Explicit user selections are handled by settings and always retain priority. */
internal object VoiceRoleDefaults {
    fun select(model: String, role: VoiceRole, author: String, voices: List<String>): String {
        if (role == VoiceRole.AUTHOR) return voices.firstOrNull { it == author } ?: voices.firstOrNull().orEmpty()
        val male = role == VoiceRole.MALE
        val candidates = when (model) {
            "teratts_v2" -> if (male) listOf("ru_m5", "ru_m1") else listOf("ru_f1", "ru_f2")
            "kokoro_ru_v2" -> if (male) listOf("dima") else listOf("masha", "sveta")
            "shtorm_pocket_ru" -> if (male) listOf("marius", "jean", "javert") else listOf("alba", "eponine", "fantine", "cosette")
            "silero_cis_ru" -> if (male) listOf("ru_alexandr", "ru_bogdan", "ru_roman") else listOf("ru_ekaterina", "ru_saida", "ru_karina")
            else -> if (male) listOf("aidar", "eugene") else listOf("kseniya", "baya", "xenia")
        }
        return candidates.firstOrNull { it in voices && it != author }
            ?: candidates.firstOrNull { it in voices } ?: voices.firstOrNull().orEmpty()
    }
}
