package com.brahmadeo.supertonic.tts.books

/** Stable identity: equal voice names in different engines must never share a choice or cache entry. */
data class BookVoiceRef(val model: String, val voice: String) {
    val key: String get() = "$model::$voice"
    companion object {
        val models = linkedMapOf("teratts_v2" to "Tera", "silero_v5_5_ru" to "Silero RU",
            "silero_cis_ru" to "Silero CIS", "kokoro_ru_v2" to "Kokoro", "shtorm_pocket_ru" to "Shtorm")
        fun parse(value: String): BookVoiceRef? {
            val pieces = value.split("::")
            if (pieces.size != 2 || pieces[0] !in models || !Regex("[a-zA-Z0-9_]+" ).matches(pieces[1])) return null
            return BookVoiceRef(pieces[0], pieces[1])
        }
        /** Rotate engines so adding a model adds variety even in a small cast. */
        fun interleave(groups: List<List<String>>): List<String> = buildList {
            for (i in 0 until (groups.maxOfOrNull { it.size } ?: 0)) for (group in groups) group.getOrNull(i)?.let(::add)
        }
        fun memoryCaption(models: List<String>, kokoroFull: Boolean): String {
            val total = 200 + models.distinct().map { when (it) {
                "teratts_v2" -> 550; "silero_v5_5_ru", "silero_cis_ru" -> 120
                "kokoro_ru_v2" -> if (kokoroFull) 1050 else 750; "shtorm_pocket_ru" -> 450; else -> 0
            } }.sum()
            return "Примерно %.1f–%.1f ГБ RAM с выбранными моделями. Это оценка, включая приложение; "
                .format(java.util.Locale("ru"), total * .85 / 1024, total * 1.25 / 1024) +
                "аудиокэш и локальная Gemma могут увеличить расход. Kokoro оценена для мужских и женских голосов. " +
                "Движки загружаются при первом использовании; больше моделей — больше памяти и дольше первый запуск."
        }
    }
}
