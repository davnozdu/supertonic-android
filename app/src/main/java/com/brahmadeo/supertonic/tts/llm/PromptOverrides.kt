package com.brahmadeo.supertonic.tts.llm

/**
 * Свои инструкции для модели: набор встроенных системных промптов и подмена любого из них.
 * Хранится в отдельном файле настроек, поэтому обычные настройки LLM (ключи, модели) остаются как есть.
 * Пустое значение = встроенный текст. Восстановление возвращает встроенный.
 */
object PromptOverrides {
    private const val PREFS = "llm_prompts"
    @Volatile private var app: android.content.Context? = null

    /** Вызывается один раз при старте процесса: дальше промпты читаются без Context. */
    fun attach(context: android.content.Context) { app = context.applicationContext }

    data class Entry(val key: String, val title: String, val about: String)

    /** Порядок как в работе приложения: сначала то, что звучит при чтении, потом книги и пересказ. */
    val ENTRIES: List<Entry> = listOf(
        Entry("stress", "Подготовка текста: ударения и пунктуация",
            "Главная инструкция облачной модели во время чтения: ударения, ё, падежи чисел и дат, пунктуация. " +
                "Приложение дописывает к ней строки о выключенных переключателях (ё, ударения, пунктуация) само."),
        Entry("verify", "Проверка спорных ударений",
            "Арбитр: решает между двумя вариантами ударения там, где словари расходятся или слово — омограф."),
        Entry("roles", "Разметка реплик (облако)",
            "Какая реплика кому принадлежит: автор, мужчина, женщина. Используется при мультиголосовой озвучке."),
        Entry("roles-local", "Разметка реплик (Gemma без облака)",
            "Та же разметка, но короткой инструкцией для небольшой локальной модели."),
        Entry("scenes", "Места действия и события книги",
            "Подготовка книги: где происходит действие, какой фон и какие звуки звучат в этом месте."),
        Entry("event-kinds", "Сведение звуков в виды действий",
            "Подготовка книги: сгруппировать похожие звуки, чтобы один звук не генерировался дважды."),
        Entry("smart", "Умная книга: пересказ",
            "Пересказ «Ранее в книге», «Эта глава», «Чем закончится» и ответы на вопросы о книге."),
        Entry("smart-plain", "Умная книга: пересказ голосом",
            "Тот же пересказ, но ответ читается сразу, без JSON и разметки.")
    )

    /** Встроенный текст промпта: то, что вернёт «Вернуть исходный». */
    fun builtIn(key: String): String = when (key) {
        "stress" -> LlmProviders.builtInTextInstruction
        "verify" -> LlmProviders.builtInStressCheck
        "roles" -> com.brahmadeo.supertonic.tts.llm.VoiceRoleProtocol.INSTRUCTION
        "roles-local" -> com.brahmadeo.supertonic.tts.llm.LocalVoiceRoleProtocol.INSTRUCTION
        "scenes" -> com.brahmadeo.supertonic.tts.books.prepare.SceneMarking.INSTRUCTION
        "event-kinds" -> com.brahmadeo.supertonic.tts.books.prepare.SceneMarking.GROUP_INSTRUCTION
        "smart" -> com.brahmadeo.supertonic.tts.books.smart.SmartPrompts.SYSTEM
        "smart-plain" -> com.brahmadeo.supertonic.tts.books.smart.SmartPrompts.SYSTEM_PLAIN
        else -> ""
    }

    /** Что отправлять модели: свой текст, если он задан, иначе встроенный. */
    fun get(key: String): String {
        val context = app ?: return builtIn(key)
        val own = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(key, "").orEmpty()
        return own.ifBlank { builtIn(key) }
    }

    /** Свой текст сохранён (в настройках видно, что инструкция не встроенная). */
    fun changed(context: android.content.Context, key: String): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(key, "").orEmpty().isNotBlank()

    fun save(context: android.content.Context, key: String, text: String) {
        val edit = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
        // Пустой текст или точное совпадение с встроенным = встроенный промпт, чтобы будущие правки приложения к нему применялись.
        if (text.isBlank() || text.trim() == builtIn(key).trim()) edit.remove(key) else edit.putString(key, text)
        edit.apply()
        com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }

    fun reset(context: android.content.Context, key: String) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().remove(key).apply()
        com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }
}
