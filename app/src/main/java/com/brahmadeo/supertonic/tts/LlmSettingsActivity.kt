package com.brahmadeo.supertonic.tts

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.llm.*
import com.brahmadeo.supertonic.tts.ui.VoiceRoleChoice
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings, one group per root-menu item ([EXTRA_SECTION]) instead of one long list. */
class LlmSettingsActivity : ComponentActivity() {
    enum class Section(val title: String) {
        LLM("Подготовка текста LLM"), MODELS("Модели LLM и ключи"), STRESS("Ударения и буква ё"),
        VOICES("Мультиголосовая озвучка"), SOUND("Звук и паузы"), AUDIO("Обработка звука"), BUFFER("Упреждение и кэш"), READERS("Сторонние читалки");
        companion object { fun from(name: String?) = entries.firstOrNull { it.name == name } ?: LLM }
    }
    companion object {
        const val EXTRA_SECTION = "section"
        fun intent(ctx: android.content.Context, section: Section) =
            android.content.Intent(ctx, LlmSettingsActivity::class.java).putExtra(EXTRA_SECTION, section.name)
    }
    private lateinit var voicePreview: VoicePreview
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        LlmPreparation.initialize(this)
        voicePreview = VoicePreview(this)
        val section = Section.from(intent.getStringExtra(EXTRA_SECTION))
        setContent {
            SupertonicTheme {
                var config by remember { mutableStateOf(LlmSettings.load(this)) }
                val previewState by voicePreview.state.collectAsState()
                val recommendations = remember { LlmModelRecommendations.load(this) }
                var busy by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("") }
                var ollamaStatus by remember { mutableStateOf("") }
                var deepseekStatus by remember { mutableStateOf("") }
                var deepseekRevision by remember { mutableIntStateOf(0) }
                var deepseekBusy by remember { mutableStateOf(false) }
                var deepseekModels by remember { mutableStateOf(runCatching {
                    val a = org.json.JSONArray(getSharedPreferences("llm_models", MODE_PRIVATE).getString("deepseek", "[]"))
                    (0 until a.length()).map { a.getString(it) }
                }.getOrDefault(emptyList())) }
                var geminiStatus by remember { mutableStateOf("") }
                var apinexStatus by remember { mutableStateOf("") }
                var apinexRevision by remember { mutableIntStateOf(0) }
                var apinexBusy by remember { mutableStateOf(false) }
                var apinexModels by remember { mutableStateOf(runCatching {
                    val a = org.json.JSONArray(getSharedPreferences("llm_models", MODE_PRIVATE).getString("apinex", "[]"))
                    (0 until a.length()).map { a.getString(it) }
                }.getOrDefault(emptyList())) }
                var ollamaRevision by remember { mutableIntStateOf(0) }
                var geminiRevision by remember { mutableIntStateOf(0) }
                var refreshing by remember { mutableStateOf<Boolean?>(null) }
                var ollamaModels by remember { mutableStateOf(loadModels(false)) }
                var geminiModels by remember { mutableStateOf(loadModels(true)) }
                var testText by remember { mutableStateOf(intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.take(2000)
                    ?: "По-прежнему светло. Ты готов? Потом мы откроем окно — и станет теплее.") }
                val scope = rememberCoroutineScope()
                val downloadStatus by LocalModelDownload.status.collectAsState()
                val downloading by LocalModelDownload.downloading.collectAsState()
                val pausePrefs = remember { getSharedPreferences("SupertonicPrefs", MODE_PRIVATE) }
                var npuEnabled by remember { mutableStateOf(pausePrefs.getBoolean(com.brahmadeo.supertonic.tts.utils.Npu.KEY, false)) }
                var shortWordCap by remember { mutableStateOf(pausePrefs.getBoolean(com.brahmadeo.supertonic.tts.tera.TeraDurationCap.KEY, true)) }
                var sileroIntonation by remember { mutableStateOf(pausePrefs.getBoolean("silero_intonation", true)) }
                var offlineReady by remember { mutableStateOf(com.brahmadeo.supertonic.tts.local.LocalRussianAssets.ready(this)) }
                var offlineBusy by remember { mutableStateOf(false) }
                var offlineStatus by remember { mutableStateOf("") }
                var sileroStress by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.StressOptions.silero(this)) }
                var ruaccentStress by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.StressOptions.ruaccent(this)) }
                var namesStress by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.StressOptions.names(this)) }
                var stressArbiter by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.StressOptions.arbiter(this)) }
                var yoSilero by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.StressOptions.yoSilero(this)) }
                var pcmCacheMb by remember { mutableIntStateOf(pausePrefs.getInt("reader_pcm_cache_mb",256)) }
                var aheadMinutes by remember { mutableIntStateOf(pausePrefs.getInt(SupertonicTTS.AHEAD_MINUTES_KEY,5)) }
                var readerAhead by remember { mutableStateOf(pausePrefs.getBoolean("reader_early_prepare",true)) }
                val sileroPauses = remember { com.brahmadeo.supertonic.tts.utils.AssetManager.isSilero(this@LlmSettingsActivity) }
                val pauseKey = if(sileroPauses) "silero_fixed_pauses" else "tera_punctuation_pauses"
                var punctuationPauses by remember { mutableStateOf(pausePrefs.getBoolean(pauseKey, !sileroPauses)) }
                var commaPause by remember { mutableIntStateOf(pausePrefs.getInt("tera_comma_pause_ms", 180)) }
                var sentencePause by remember { mutableIntStateOf(pausePrefs.getInt("tera_sentence_pause_ms", 420)) }
                fun save() {
                    try { LlmSettings.save(this, config); message = "Настройки сохранены" }
                    catch (_: Exception) { message = "Не удалось сохранить ключи в Android Keystore" }
                }
                fun refresh(gemini: Boolean) {
                    fun status(value: String) { if (gemini) geminiStatus = value else ollamaStatus = value }
                    busy = true; refreshing = gemini; status("Получение актуальных моделей…")
                    val snapshot = config
                    scope.launch {
                        try {
                            LlmSettings.save(this@LlmSettingsActivity, snapshot)
                            val provider = if (gemini) "gemini" else "ollama"
                            val models = withContext(Dispatchers.IO) { LlmProviders.models(snapshot, gemini) }
                                .sortedWith(compareBy<String> { recommendations["$provider/$it"]?.priority ?: 100 }.thenBy { it })
                            if (gemini) { geminiModels = models; geminiRevision++ } else { ollamaModels = models; ollamaRevision++ }
                            saveModels(gemini, models)
                            status(if (models.isEmpty()) "API не вернул доступных моделей" else "Получено моделей: ${models.size}. Выберите модель ниже.")
                        } catch (e: Exception) { status("Не удалось получить модели: ${e.message?.take(160)}") }
                        finally { busy = false; refreshing = null }
                    }
                }
                Scaffold(topBar = { TopAppBar(title = { Text(section.title) }, navigationIcon = {
                    TextButton(onClick = { finish() }) { Text("Назад") }
                }) }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        fun option(key: String, value: Boolean) = pausePrefs.edit().putBoolean(key, value).apply()
                        if (section == Section.LLM) {
                        Toggle("LLM-обработка", config.mode != LlmMode.OFF) {
                            config = config.copy(mode = if (it) LlmSettings.previousMode(this@LlmSettingsActivity) else LlmMode.OFF)
                            save()
                        }
                        if (config.mode != LlmMode.OFF) {
                            Choice("Режим", config.mode.title, LlmMode.entries.filter { it != LlmMode.OFF }.map { it.title }) { title ->
                                config = config.copy(mode = LlmMode.entries.first { it.title == title }); save()
                            }
                        }
                        Text("Ударения и пунктуация готовятся до синтеза. При ошибке или превышении времени ожидания используется обычная обработка со словарём.")
                        Text("Целые числа сначала точно переводятся в слова. LLM ставит пунктуацию и согласует один/одна/одно, два/две внутри числительных. Изменение значения числа, остальных слов или границ абзацев запрещено; такой ответ отклоняется. Ударения и ё — в пункте меню «Ударения и буква ё».", style = MaterialTheme.typography.bodySmall)
                        Toggle("Восстанавливать пунктуацию", config.punctuation) { config = config.copy(punctuation = it); save() }
                        Text("Текст, уже отправленный читалкой, подготавливается в фоне с контекстом до 4000 символов. Если читалка отправляет по одному фрагменту, первая подготовка каждого нового фрагмента может занять время.", style = MaterialTheme.typography.bodySmall)
                        Text("Чтение ждёт подготовку не более 1,5 секунды. Если результат ещё не готов, используется словарь, а очередь подготавливается дальше в фоне.", style = MaterialTheme.typography.bodySmall)
                        Text("Ключи шифруются Android Keystore. При выборе облака текст передаётся выбранному провайдеру. Проверка ниже обрабатывает только введённый отрывок и не воспроизводит звук.", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        OutlinedTextField(testText, { testText = it.take(2000) }, label = { Text("Отрывок для проверки") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                        Button(enabled = !busy && config.mode != LlmMode.OFF, onClick = {
                            busy = true; message = "Обработка отрывка…"
                            val snapshot = config; val sample = testText
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { LlmPreparation.test(this@LlmSettingsActivity, snapshot, sample) }
                                    message = "${result.provider}, ${result.elapsedMs} мс${if (result.fallback) " — резервная обработка; ${result.reason.orEmpty()}" else ""}\n\n${result.text}"
                                } catch (_: Exception) { message = "Проверка не завершилась; проверьте модель, ключ и сеть" }
                                finally { busy = false }
                            }
                        }) { Text("Проверить без озвучивания") }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (message.isNotEmpty()) Text(message)
                        }
                        if (section == Section.MODELS) {
                        Text("Ollama Cloud", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(config.ollamaEndpoint, { config = config.copy(ollamaEndpoint = it) }, label = { Text("Адрес API (HTTPS)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(config.ollamaKey, { config = config.copy(ollamaKey = it) }, label = { Text("Ключ Ollama") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { refresh(false) }, enabled = !busy) { Text("Считать актуальные модели Ollama") }
                        if (refreshing == false) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (ollamaStatus.isNotEmpty()) Text(ollamaStatus)
                        Choice("Модель Ollama", config.ollamaModel.ifBlank { "Выберите модель" }, ollamaModels, ollamaRevision,
                            format = { name -> recommendations["ollama/$name"]?.let { "★ $name — ${it.label}" } ?: name },
                            highlight = { recommendations.containsKey("ollama/$it") }) { config = config.copy(ollamaModel = it); save() }
                        Toggle("Размышление в Ollama (медленнее)", config.ollamaThinking) { config = config.copy(ollamaThinking = it); save() }
                        Text("По умолчанию выключено. Если модель разрешает только уровни размышления, при выключении выбирается минимальный доступный уровень.", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("DeepSeek", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(config.deepseekKey, { config = config.copy(deepseekKey = it) }, label = { Text("Ключ DeepSeek API") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            val snapshot = config
                            busy = true; deepseekBusy = true; deepseekStatus = "Получение актуальных моделей…"
                            scope.launch {
                                try {
                                    LlmSettings.save(this@LlmSettingsActivity, snapshot)
                                    val models = withContext(Dispatchers.IO) { LlmProviders.models(snapshot, false, "deepseek") }
                                    deepseekModels = models; deepseekRevision++
                                    getSharedPreferences("llm_models", MODE_PRIVATE).edit().putString("deepseek", org.json.JSONArray(models).toString()).apply()
                                    deepseekStatus = if (models.isEmpty()) "API не вернул доступных моделей" else "Получено моделей: ${models.size}. Выберите модель ниже."
                                } catch (e: Exception) { deepseekStatus = "Не удалось получить модели: ${e.message?.take(160)}" }
                                finally { busy = false; deepseekBusy = false }
                            }
                        }, enabled = !busy) { Text("Считать актуальные модели DeepSeek") }
                        if (deepseekBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (deepseekStatus.isNotEmpty()) Text(deepseekStatus)
                        Choice("Модель DeepSeek", config.deepseekModel, deepseekModels, deepseekRevision,
                            format = { if (it == DeepSeekApi.DEFAULT_MODEL) "DeepSeek 4.1 Flash · $it" else it }) {
                            config = config.copy(deepseekModel = it); save()
                        }
                        Toggle("Размышление в DeepSeek (медленнее)", config.deepseekThinking) { config = config.copy(deepseekThinking = it); save() }
                        Text("По умолчанию DeepSeek 4.1 Flash, размышление выключено. Ключ хранится зашифрованным в Android Keystore.", style = MaterialTheme.typography.bodySmall)
                        Toggle("В авторежиме сначала DeepSeek", config.preferDeepseek) { config = config.copy(preferDeepseek = it,
                            preferGemini = if (it) false else config.preferGemini, preferApinex = if (it) false else config.preferApinex); save() }
                        HorizontalDivider()
                        Text("APInex", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(config.apinexKey, { config = config.copy(apinexKey = it) }, label = { Text("Ключ APInex (sk-apx…)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            val snapshot = config
                            busy = true; apinexBusy = true; apinexStatus = "Получение актуальных моделей…"
                            scope.launch {
                                try {
                                    LlmSettings.save(this@LlmSettingsActivity, snapshot)
                                    val models = withContext(Dispatchers.IO) { LlmProviders.models(snapshot, false, "apinex") }
                                    apinexModels = models; apinexRevision++
                                    getSharedPreferences("llm_models", MODE_PRIVATE).edit().putString("apinex", org.json.JSONArray(models).toString()).apply()
                                    apinexStatus = if (models.isEmpty()) "API не вернул доступных моделей" else "Получено моделей: ${models.size}. Выберите модель ниже."
                                } catch (e: Exception) { apinexStatus = "Не удалось получить модели: ${e.message?.take(160)}" }
                                finally { busy = false; apinexBusy = false }
                            }
                        }, enabled = !busy) { Text("Считать актуальные модели APInex") }
                        if (apinexBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (apinexStatus.isNotEmpty()) Text(apinexStatus)
                        Choice("Модель APInex", config.apinexModel, apinexModels, apinexRevision,
                            format = { if (it == ApinexApi.DEFAULT_MODEL) "DeepSeek 4.1 Flash, бесплатно · $it" else it }) {
                            config = config.copy(apinexModel = it); save()
                        }
                        Toggle("Размышление в APInex (медленнее)", config.apinexThinking) { config = config.copy(apinexThinking = it); save() }
                        Text("Шлюз-посредник: текст уходит APInex, а от него — поставщику модели; их политика хранения данных не опубликована. " +
                            "Лимит запросов в минуту (бесплатный план — 15) MyTTS берёт из API APInex и сам выдерживает интервал. Для пересказа " +
                            "«Умной книги» этого хватает с запасом; при постоянной подготовке текста во время чтения часть абзацев может пойти по словарям. " +
                            "Ключ хранится зашифрованным в Android Keystore.", style = MaterialTheme.typography.bodySmall)
                        Toggle("В авторежиме сначала APInex", config.preferApinex) { config = config.copy(preferApinex = it,
                            preferGemini = if (it) false else config.preferGemini, preferDeepseek = if (it) false else config.preferDeepseek); save() }
                        HorizontalDivider()
                        Text("Gemini", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(config.geminiKey, { config = config.copy(geminiKey = it) }, label = { Text("Ключ Gemini API") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { refresh(true) }, enabled = !busy) { Text("Считать актуальные модели Gemini") }
                        if (refreshing == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (geminiStatus.isNotEmpty()) Text(geminiStatus)
                        Choice("Модель Gemini", config.geminiModel.ifBlank { "Выберите модель" }, geminiModels, geminiRevision,
                            format = { name -> recommendations["gemini/$name"]?.let { "★ $name — ${it.label}" } ?: name },
                            highlight = { recommendations.containsKey("gemini/$it") }) { config = config.copy(geminiModel = it); save() }
                        Toggle("Размышление в Gemini (медленнее)", config.geminiThinking) { config = config.copy(geminiThinking = it); save() }
                        if (ThinkingPolicy.gemini(config.geminiModel, false)?.minimumOnly == true) {
                            Text("У этой модели API не позволяет полностью отключить размышление. Выключенный тумблер устанавливает минимальный уровень.", style = MaterialTheme.typography.bodySmall)
                        } else if (config.geminiModel.isNotBlank() && ThinkingPolicy.gemini(config.geminiModel, false) == null) {
                            Text("Для этой модели API не предоставляет известной настройки размышления; тумблер к ней не применяется.", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("API может вернуть также модели звука и изображений. Для подготовки текста выберите текстовую LLM.", style = MaterialTheme.typography.bodySmall)
                        Toggle("В авторежиме сначала Gemini", config.preferGemini) { config = config.copy(preferGemini = it, preferDeepseek = if (it) false else config.preferDeepseek, preferApinex = if (it) false else config.preferApinex); save() }
                        Text("Авто пробует настроенные облака по порядку, затем скачанную Gemma 4. Ручной выбор облака имеет приоритет; при его сбое используется Gemma 4. Автономный режим никогда не обращается к облакам.", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("Локальная Gemma 4 E2B", style = MaterialTheme.typography.titleLarge)
                        val llmPrefs = remember { getSharedPreferences("llm_settings", MODE_PRIVATE) }
                        val npuPossible = remember { com.brahmadeo.supertonic.tts.llm.GemmaHexagon.supported(this@LlmSettingsActivity) }
                        var engine by remember { mutableStateOf(llmPrefs.getString(LocalModelDownload.ENGINE_KEY, "gpu") ?: "gpu") }
                        if (npuPossible) {
                            // One local engine at a time: choosing one switches the other off.
                            Choice("Движок локальной Gemma", if (engine == "npu") "NPU · Hexagon (эксперимент)" else "GPU · LiteRT",
                                listOf("GPU · LiteRT", "NPU · Hexagon (эксперимент)")) {
                                engine = if (it.startsWith("NPU")) "npu" else "gpu"
                                llmPrefs.edit().putString(LocalModelDownload.ENGINE_KEY, engine).apply()
                                scope.launch { withContext(Dispatchers.IO) { LlmProviders.unload() } }
                                com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
                            }
                            Text(if (engine == "npu") "Та же Gemma 4 E2B в формате Q4_0 на нейропроцессоре Snapdragon (llama.cpp ggml-hexagon), отдельный файл 2,44 ГБ. Цель — меньше нагрузки на процессор и батарею; при сбое NPU локальная обработка не подменяется GPU, работает обычный резерв."
                                else "Модель LiteRT на графическом процессоре (при ошибке — CPU). NPU-вариант можно выбрать здесь же; одновременно работает только один.", style = MaterialTheme.typography.bodySmall)
                        }
                        val localSpec = if (engine == "npu" && npuPossible) LocalModelDownload.HEXAGON else LocalModelDownload.LITERT
                        Text(if (LocalModelDownload.ready(this@LlmSettingsActivity, localSpec)) "Модель установлена и проверена" else downloadStatus)
                        Text("Скачивание из Hugging Face: ${localSpec.sizeLabel}. После установки модель работает без сети и без ключей. Скачанный файл остаётся на устройстве; из RAM модель выгружается после простоя.", style = MaterialTheme.typography.bodySmall)
                        if (downloading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            OutlinedButton(onClick = { LocalModelDownload.cancel(this@LlmSettingsActivity) }) { Text("Приостановить скачивание") }
                        } else if (!LocalModelDownload.ready(this@LlmSettingsActivity, localSpec)) {
                            Button(enabled = LocalModelDownload.supported(), onClick = { LocalModelDownload.start(this@LlmSettingsActivity, localSpec) }) { Text("Поставить / скачать локальную модель") }
                            if (!LocalModelDownload.supported()) Text("Для локальной Gemma 4 требуется 64-битный Android")
                        }
                        if (engine != "npu" || !npuPossible) {
                        Toggle("GPU для Gemma 4 (при ошибке — CPU)", config.gpu) { config = config.copy(gpu = it); save() }
                        var speculative by remember { mutableStateOf(getSharedPreferences("llm_settings", MODE_PRIVATE).getBoolean(LlmProviders.SPECULATIVE_KEY, false)) }
                        Toggle("Ускоренная генерация Gemma · эксперимент", speculative) {
                            speculative = it
                            getSharedPreferences("llm_settings", MODE_PRIVATE).edit().putBoolean(LlmProviders.SPECULATIVE_KEY, it).apply()
                        }
                        Text("Спекулятивное декодирование LiteRT-LM: черновые токены проверяет основная модель, текст тот же. По данным Google на Snapdragon того же поколения генерация на GPU ускоряется примерно с 52 до 87 токенов/с.", style = MaterialTheme.typography.bodySmall)
                        }
                        Toggle("Размышление в локальной Gemma 4 (медленнее)", config.localThinking) { config = config.copy(localThinking = it); save() }
                        Choice("Выгрузка из RAM после простоя", "${config.idleSeconds} секунд", listOf("30 секунд", "60 секунд", "120 секунд", "300 секунд", "600 секунд")) {
                            config = config.copy(idleSeconds = it.substringBefore(' ').toInt()); save()
                        }
                        OutlinedButton(onClick = {
                            scope.launch { withContext(Dispatchers.IO) { LlmProviders.unload() }; message = "Локальная модель выгружена из RAM" }
                        }) { Text("Выгрузить из памяти сейчас") }
                        Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить настройки") }
                        }
                        if (section == Section.STRESS) {
                        Text("Каждый источник включается отдельно. По умолчанию только Silero Stress: акцентор и модель омографов на телефоне, без сети. Ударения и ё, уже написанные в книге или в словаре пользователя, сохраняются всегда.", style = MaterialTheme.typography.bodySmall)
                        Toggle("Silero Stress", sileroStress) { sileroStress = it; option(com.brahmadeo.supertonic.tts.utils.StressOptions.SILERO, it) }
                        if (!offlineReady) Text("Ресурсы Silero (ударения и словарь ё) ещё не скачаны — кнопка ниже. До скачивания эти переключатели не действуют.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Toggle("Словарь RUAccent (179 МБ, скачивается сам по Wi-Fi)", ruaccentStress) { ruaccentStress = it; option(com.brahmadeo.supertonic.tts.utils.StressOptions.RUACCENT, it) }
                        Text("Голосует вместе с Silero: совпавшее ударение окончательное, при расхождении без арбитра — словарь RUAccent, для омографа — Silero.", style = MaterialTheme.typography.bodySmall)
                        Toggle("Словарь имён: ударение и ё только в именах (Семен → Семён)", namesStress) { namesStress = it; option(com.brahmadeo.supertonic.tts.utils.StressOptions.NAMES, it) }
                        Text("Около 27 тысяч русских имён и отчеств из Викисловаря: ставит ударение и ё только в словах с заглавной буквы. Обычные слова (черный, ребенок) не трогает — для них «Буква ё: словарь Silero».", style = MaterialTheme.typography.bodySmall)
                        Toggle("Ударения от LLM", config.stress) { config = config.copy(stress = it); save() }
                        Toggle("LLM-арбитр для споров и омографов", stressArbiter) { stressArbiter = it; option(com.brahmadeo.supertonic.tts.utils.StressOptions.ARBITER, it) }
                        Text("Арбитр — облачная LLM: спорное слово спрашивается дважды в разном порядке вариантов. Работает при включённой LLM-обработке и двух мнениях (Silero и RUAccent или LLM); локальная Gemma арбитром не бывает.", style = MaterialTheme.typography.bodySmall)
                        Toggle("Буква ё: словарь Silero (черный → чёрный)", yoSilero) { yoSilero = it; option(com.brahmadeo.supertonic.tts.utils.StressOptions.YO_SILERO, it) }
                        Toggle("Буква ё: LLM по контексту (все/всё, узнает/узнаёт)", config.restoreYo) { config = config.copy(restoreYo = it); save() }
                        if (!sileroStress && !ruaccentStress && !config.stress) Text("Все источники ударений выключены: модель прочитает слова со своими догадками.", style = MaterialTheme.typography.bodySmall)
                        // Switches that the current combination leaves without effect, said plainly.
                        val llmOff = config.mode == LlmMode.OFF
                        if (!yoSilero && !namesStress && (llmOff || !config.restoreYo))
                            Text("Все источники ё выключены: книга, набранная без ё, прочитается через е (черный, ребенок). Обычно достаточно «Буква ё: словарь Silero».",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        else if (!yoSilero && namesStress)
                            Text("ё ставится только в именах; в обычных словах (черный, ребенок) — только если её поставит LLM. Для них включите «Буква ё: словарь Silero».",
                                style = MaterialTheme.typography.bodySmall)
                        if (llmOff && (config.stress || config.restoreYo || stressArbiter))
                            Text("LLM-обработка выключена: «Ударения от LLM», «ё от LLM» и арбитр сейчас не действуют, работают только словари.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (!llmOff && stressArbiter && config.mode == LlmMode.LOCAL)
                            Text("Режим «Автономно: Gemma 4»: арбитр не работает (Gemma не решает споры ударений), спор решают словари.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (!llmOff && stressArbiter && config.mode != LlmMode.LOCAL &&
                            !((sileroStress && ruaccentStress) || ((sileroStress || ruaccentStress) && config.stress)))
                            Text("Арбитру нечего решать: нужны два мнения — Silero Stress и RUAccent, или один из них вместе с «Ударения от LLM».",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (!llmOff && (config.stress || config.restoreYo))
                            Text("В читалках, которые присылают по одному предложению (KOReader, Readest), при включённом «Не ждать LLM в читалках без очереди» LLM не используется: ударения и ё там ставят только словари.",
                                style = MaterialTheme.typography.bodySmall)
                        Text("Изменение настроек сбрасывает кэш подготовленного текста и аудио.", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("Локальные ресурсы", style = MaterialTheme.typography.titleLarge)
                            Text("Числа, даты, время, годы, единицы, валюты, дроби, сокращения и сноски обрабатываются на телефоне даже при выключенной LLM.")
                            Button(enabled = !offlineBusy && !offlineReady, onClick = {
                                offlineBusy = true
                                scope.launch {
                                    try {
                                        com.brahmadeo.supertonic.tts.local.LocalRussianAssets.download(this@LlmSettingsActivity) { status,_ -> runOnUiThread { offlineStatus = status } }
                                        offlineReady = true
                                    } catch (t: Exception) { offlineStatus = "Ошибка установки: ${t.message}" }
                                    finally { offlineBusy = false }
                                }
                            }) { Text(if (offlineReady) "Локальный акцентор установлен" else "Скачать локальные ударения и ё · 37 МБ") }
                            if (offlineStatus.isNotBlank()) Text(offlineStatus)
                        }
                        if (section == Section.VOICES) {
                        if (com.brahmadeo.supertonic.tts.utils.AssetManager.isRussianModel(this@LlmSettingsActivity)) {
                            Text("Мультиголосовая озвучка · эксперимент", style = MaterialTheme.typography.titleMedium)
                            Toggle("Читать автора и диалоги разными голосами", config.multiVoice) {
                                voicePreview.stop()
                                config = config.copy(multiVoice = it); save()
                            }
                            Text("LLM определяет автора, мужские и женские реплики по соседнему тексту. Слова книги сохраняются. Неясные реплики и фрагменты без готовой разметки читает автор. Голоса сохраняются отдельно для каждой звуковой модели.", style = MaterialTheme.typography.bodySmall)
                            if (config.multiVoice) {
                                Toggle("Текст от первого лица — всегда", config.firstPerson) {
                                    config = config.copy(firstPerson = it); save()
                                }
                                Text("Рассказчик говорит «я»: его собственные реплики («— сказал я») читает голос автора. Действует для всех текстов; " +
                                    "для отдельной книги можно включить или выключить на экране «Книги с голосами персонажей».", style = MaterialTheme.typography.bodySmall)
                                if (config.mode == LlmMode.OFF) Text("Включите LLM-обработку для определения ролей. При выключенной LLM используется обычный выбранный голос.")
                                val roleVoices = com.brahmadeo.supertonic.tts.utils.AssetManager.russianVoices(this@LlmSettingsActivity)
                                var roleRevision by remember { mutableStateOf(0) }
                                VoiceRole.entries.forEach { role ->
                                    var selected by remember(role, roleVoices, roleRevision) { mutableStateOf(MultiVoiceSettings.selected(this@LlmSettingsActivity, role)) }
                                    VoiceRoleChoice(when(role) { VoiceRole.AUTHOR -> "Голос автора"; VoiceRole.MALE -> "Мужской голос"; VoiceRole.FEMALE -> "Женский голос" }, selected, roleVoices,
                                        previewState, voicePreview::toggle) {
                                        voicePreview.stop(); selected = it; MultiVoiceSettings.save(this@LlmSettingsActivity, role, it); roleRevision++
                                    }
                                }
                                OutlinedButton(onClick = { startActivity(android.content.Intent(this@LlmSettingsActivity, BooksActivity::class.java)) }) {
                                    Text("Книги с голосами персонажей")
                                }
                                if (VoiceRole.entries.map { MultiVoiceSettings.selected(this@LlmSettingsActivity, it) }.distinct().size < 3)
                                    Text("Для нескольких ролей выбран одинаковый голос. Чтобы слышать переключение, выберите три разных голоса.", style = MaterialTheme.typography.bodySmall)
                                Text("При обрыве облака используется установленная Gemma 4. Повторные запросы идут в фоне; готовая разметка заменяет резервную в ещё не проигранных фрагментах.", style = MaterialTheme.typography.bodySmall)
                                if (previewState.message.isNotBlank()) Text(previewState.message, style = MaterialTheme.typography.bodySmall)
                                Text("У каждого голоса есть кнопка «Прослушать», в том числе в списке выбора. Повторное нажатие останавливает пробу. Для сравнения используется одинаковая короткая фраза без LLM и фоновой музыки.", style = MaterialTheme.typography.bodySmall)
                                Text("Подготовка идёт заранее в пределах очереди читалки. Чтение не ждёт разметку дольше обычного лимита. Облако рекомендуется; локальная Gemma может ошибаться в ролях.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (com.brahmadeo.supertonic.tts.utils.AssetManager.isRussianModel(this@LlmSettingsActivity)) {
                            var levelVoices by remember { mutableStateOf(pausePrefs.getBoolean("voice_loudness_normalization", true)) }
                            Toggle("Выравнивать громкость голосов", levelVoices) {
                                levelVoices = it; pausePrefs.edit().putBoolean("voice_loudness_normalization", it).apply()
                                com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
                            }
                            Text("Одинаковый ориентир громкости для Silero, Tera, Kokoro и Shtorm. Усиление постоянно внутри фразы: ритм и паузы сохраняются.", style = MaterialTheme.typography.bodySmall)
                            var levelTempo by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.VoiceTempo.enabled(this@LlmSettingsActivity)) }
                            Toggle("Выравнивать темп голосов", levelTempo) {
                                levelTempo = it; option(com.brahmadeo.supertonic.tts.utils.VoiceTempo.KEY, it)
                            }
                            Text("У каждого голоса свой естественный темп: один читает медленнее, другой быстрее. Каждый голос один раз замеряется на одинаковой фразе, и его скорость подстраивается под выбранный голос (голос автора). Работает для ролей, персонажей книг и разных моделей.", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Иностранные фрагменты и голос для Readest — в пункте меню «Мультиязычная озвучка».", style = MaterialTheme.typography.bodySmall)
                        }
                        if (section == Section.SOUND) {
                        if(com.brahmadeo.supertonic.tts.utils.AssetManager.isTera(this@LlmSettingsActivity)) {
                            Text("Звук Tera",style=MaterialTheme.typography.titleMedium)
                            Text("Быстрая, сжатая int8 или Teacher — выбор «Синтез Tera» на главном экране под стилем голоса.",style=MaterialTheme.typography.bodySmall)
                            var noiseScale by remember { mutableStateOf(pausePrefs.getFloat(com.brahmadeo.supertonic.tts.tera.TeraEngine.NOISE_SCALE, 1f)) }
                            Text("Стабильность ↔ выразительность: ${"%.2f".format(noiseScale)}${if (noiseScale >= 0.995f) " (как у модели)" else ""}")
                            Slider(value = noiseScale, valueRange = 0.4f..1f, steps = 11,
                                onValueChange = { noiseScale = (Math.round(it * 20) / 20f).coerceIn(0.4f, 1f) },
                                onValueChangeFinished = { pausePrefs.edit().putFloat(com.brahmadeo.supertonic.tts.tera.TeraEngine.NOISE_SCALE, noiseScale).apply() })
                            Text("Разброс начального шума синтеза. 1.0 — как задумано моделью. Меньше — ровнее: реже пропускает и повторяет слова, но интонация площе (в форке TeraTTS по умолчанию 0.67). Сравните на слух. Нагрузку не меняет.",style=MaterialTheme.typography.bodySmall)
                            var lowerNames by remember { mutableStateOf(pausePrefs.getBoolean(com.brahmadeo.supertonic.tts.tera.TeraEngine.LOWER_NAMES, true)) }
                            Toggle("Имена внутри предложения — строчными", lowerNames) {
                                lowerNames = it
                                pausePrefs.edit().putBoolean(com.brahmadeo.supertonic.tts.tera.TeraEngine.LOWER_NAMES, it).apply()
                            }
                            Text("Tera принимает слово с заглавной буквы за иностранное имя и читает «Тень» как «тэнь». Для модели такие слова пишутся строчными; ударения уже расставлены, первое слово предложения не меняется.",style=MaterialTheme.typography.bodySmall)
                            Toggle("Не растягивать короткие реплики",shortWordCap) {
                                shortWordCap=it
                                pausePrefs.edit().putBoolean(com.brahmadeo.supertonic.tts.tera.TeraDurationCap.KEY,it).apply()
                            }
                            Text("Модель даёт «Да.», «Нет.» и «Хорошо.» почти секунду. Для фраз до четырёх гласных длительность ограничивается обычным темпом речи; длинные фразы не меняются.",style=MaterialTheme.typography.bodySmall)
                            val npuSupported = remember { com.brahmadeo.supertonic.tts.utils.Npu.supported(this@LlmSettingsActivity) }
                            if (npuSupported) {
                                Toggle("Ускоритель NPU · эксперимент", npuEnabled) {
                                    npuEnabled = it
                                    if (it) com.brahmadeo.supertonic.tts.utils.Npu.clearFailure(this@LlmSettingsActivity)
                                    pausePrefs.edit().putBoolean(com.brahmadeo.supertonic.tts.utils.Npu.KEY, it).apply()
                                }
                                Text(if (npuEnabled && com.brahmadeo.supertonic.tts.utils.Npu.failed(this@LlmSettingsActivity))
                                    "NPU не запустился на этом телефоне; используется процессор."
                                    else "Вокодер Tera выполняется на нейропроцессоре Snapdragon (FP16). Первый запуск компилирует модель несколько секунд. При ошибке чтение продолжается на процессоре.",
                                    style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        if(com.brahmadeo.supertonic.tts.utils.AssetManager.isPocket(this@LlmSettingsActivity)) {
                            Text("Звук Shtorm",style=MaterialTheme.typography.titleMedium)
                            var temperature by remember { mutableStateOf(com.brahmadeo.supertonic.tts.pocket.PocketEngine.temperature(this@LlmSettingsActivity)) }
                            Text("Стабильность ↔ выразительность: ${"%.2f".format(temperature)}${if (kotlin.math.abs(temperature - .3f) < .001f) " (по умолчанию)" else ""}")
                            Slider(value = temperature, valueRange = 0.1f..0.8f, steps = 13,
                                onValueChange = { temperature = (Math.round(it * 20) / 20f).coerceIn(.1f, .8f) },
                                onValueChangeFinished = { pausePrefs.edit().putFloat(com.brahmadeo.supertonic.tts.pocket.PocketEngine.TEMPERATURE, temperature).apply() })
                            Text("Температура выборки Shtorm. Меньше — ровнее и предсказуемее, больше — живее интонация, но чаще сбои и оговорки. Сравните на слух. Нагрузку не меняет.",style=MaterialTheme.typography.bodySmall)
                        }
                        if(com.brahmadeo.supertonic.tts.utils.AssetManager.isKokoro(this@LlmSettingsActivity)) {
                            Text("Звук Kokoro",style=MaterialTheme.typography.titleMedium)
                            if (com.brahmadeo.supertonic.tts.utils.Npu.supported(this@LlmSettingsActivity)) {
                                Toggle("Ускоритель NPU · эксперимент", npuEnabled) {
                                    npuEnabled = it
                                    if (it) com.brahmadeo.supertonic.tts.utils.Npu.clearFailure(this@LlmSettingsActivity)
                                    pausePrefs.edit().putBoolean(com.brahmadeo.supertonic.tts.utils.Npu.KEY, it).apply()
                                }
                                Text(if (npuEnabled && com.brahmadeo.supertonic.tts.utils.Npu.failed(this@LlmSettingsActivity, com.brahmadeo.supertonic.tts.utils.Npu.KOKORO))
                                    "NPU не запустился для Kokoro; используется процессор."
                                    else "Генератор звука Kokoro считается на нейропроцессоре Snapdragon (FP16), текст и финальная сборка звука — на процессоре. Первый запуск около минуты готовит графы в фоне; в это время чтение идёт на процессоре. Переключатель общий с Tera.",
                                    style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (com.brahmadeo.supertonic.tts.utils.AssetManager.isSilero(this@LlmSettingsActivity) && !com.brahmadeo.supertonic.tts.silero.SileroDownload.cis(this@LlmSettingsActivity)) {
                            Toggle("Silero: вопросительная и восклицательная интонация", sileroIntonation) {
                                sileroIntonation = it; pausePrefs.edit().putBoolean("silero_intonation", it).apply()
                            }
                            Text("Тип предложения передаётся прямо в звуковую модель. При выключении используется повествовательная интонация.", style = MaterialTheme.typography.bodySmall)
                        }
                        val sentencePauseOnly = com.brahmadeo.supertonic.tts.utils.AssetManager.isKokoro(this@LlmSettingsActivity) || com.brahmadeo.supertonic.tts.utils.AssetManager.isPocket(this@LlmSettingsActivity)
                        Text("Паузы", style = MaterialTheme.typography.titleLarge)
                        Toggle(if(sileroPauses) "Дополнительные фиксированные паузы Silero" else "Слышимые паузы по пунктуации", punctuationPauses) {
                            punctuationPauses = it; pausePrefs.edit().putBoolean(pauseKey, it).apply()
                        }
                        if (sileroPauses && punctuationPauses) {
                            var everyComma by remember { mutableStateOf(pausePrefs.getBoolean("silero_every_comma", false)) }
                            Toggle("Пауза на каждой запятой", everyComma) { everyComma = it; option("silero_every_comma", it) }
                            Text(if (everyComma) "Пауза ставится на каждой запятой, включая вводные слова и перечисления — так чтение может звучать «спотыкаясь»."
                                else "Пауза только там, где запятая разделяет целые части фразы. После вводных слов и обращений («Впрочем,», «Послушай,»), в перечислениях («хлеб, воду, нож») и перед «что/чтобы/как» после короткой фразы («Я знал, что…») модель читает в своём ритме, без остановки.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        // Kokoro and Shtorm pace commas themselves; only the sentence end gets the missing silence (SupertonicTTS.sentencePauseMs).
                        if (!sentencePauseOnly) Choice("Запятая", "$commaPause мс", listOf("100 мс", "140 мс", "180 мс", "220 мс", "300 мс")) {
                            commaPause = it.substringBefore(' ').toInt(); pausePrefs.edit().putInt("tera_comma_pause_ms", commaPause).apply()
                        }
                        Choice("Точка, вопрос и восклицание", "$sentencePause мс", listOf("0 мс", "150 мс", "250 мс", "350 мс", "420 мс", "550 мс", "700 мс")) {
                            sentencePause = it.substringBefore(' ').toInt(); pausePrefs.edit().putInt("tera_sentence_pause_ms", sentencePause).apply()
                        }
                        Text(when {
                            sileroPauses -> "По умолчанию используется естественный ритм Silero: модель сама задаёт паузы и сохраняет связную интонацию. Дополнительные паузы можно включить вручную."
                            sentencePauseOnly -> "Модель сама делает паузы на запятых. После точки, вопроса и восклицания добавляется только недостающая тишина до выбранной длины. Меньше — чтение плавнее; в читалках, которые присылают по одному предложению (KOReader, Readest), это и есть пауза между предложениями."
                            else -> "Работает также при выключенной LLM. Учитывает тишину, уже сгенерированную моделью, и добавляет только недостающую паузу."
                        }, style = MaterialTheme.typography.bodySmall)
                        }
                        if (section == Section.SOUND) {
                            HorizontalDivider()
                            val bracketTitles = mapOf(
                                com.brahmadeo.supertonic.tts.utils.BracketReading.Mode.WORDS to "Словами",
                                com.brahmadeo.supertonic.tts.utils.BracketReading.Mode.PAUSE to "Паузой",
                                com.brahmadeo.supertonic.tts.utils.BracketReading.Mode.ASIDE to "Отдельной фразой",
                                com.brahmadeo.supertonic.tts.utils.BracketReading.Mode.OFF to "Не выделять")
                            var bracketMode by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.BracketReading.from(
                                pausePrefs.getString(com.brahmadeo.supertonic.tts.utils.BracketReading.KEY, null))) }
                            Choice("Скобки", bracketTitles.getValue(bracketMode), bracketTitles.values.toList()) { title ->
                                bracketMode = bracketTitles.entries.first { it.value == title }.key
                                pausePrefs.edit().putString(com.brahmadeo.supertonic.tts.utils.BracketReading.KEY, bracketMode.name).apply()
                            }
                            Text(when (bracketMode) {
                                com.brahmadeo.supertonic.tts.utils.BracketReading.Mode.WORDS -> "Текст в скобках читается со словами «скобка открывается … скобка закрывается»."
                                com.brahmadeo.supertonic.tts.utils.BracketReading.Mode.PAUSE -> "Текст в скобках выделяется паузами, как запятыми; слово «скобка» не произносится."
                                com.brahmadeo.supertonic.tts.utils.BracketReading.Mode.ASIDE -> "Пояснение в скобках читается как у чтеца: отдельной фразой, с паузой вокруг, чуть быстрее и тише. Короткие вставки вроде «(с)» читаются подряд. Если LLM уберёт скобки, фраза прочитается обычно."
                                else -> "Скобки не выделяются: текст в них читается подряд с остальной фразой."
                            } + " Сноски вида [1] или (1) не читаются. Работает для всех моделей, с LLM и без неё.", style = MaterialTheme.typography.bodySmall)
                        }
                        if (section == Section.AUDIO) {
                        Text("Фильтры применяются к готовому звуку всех русских моделей, до кэша. Каждый включается отдельно — переключите и послушайте: кэш звука сбрасывается, следующие фразы звучат уже с новой настройкой.", style = MaterialTheme.typography.bodySmall)
                        var f_fade by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.SpeechFilters.enabled(this@LlmSettingsActivity, com.brahmadeo.supertonic.tts.utils.SpeechFilters.FADE)) }
                        Toggle("Сглаживание краёв фразы", f_fade) { f_fade = it; option(com.brahmadeo.supertonic.tts.utils.SpeechFilters.FADE, it) }
                        Text("Убирает щелчок, когда фраза начинается или кончается не с тишины. Нарастание и затухание 5 мс — на слух не заметно. Нагрузка: практически нулевая (только 5 мс в начале и в конце фразы).", style = MaterialTheme.typography.bodySmall)
                        var f_high_pass by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.SpeechFilters.enabled(this@LlmSettingsActivity, com.brahmadeo.supertonic.tts.utils.SpeechFilters.HIGH_PASS)) }
                        Toggle("Срез гула ниже 70 Гц", f_high_pass) { f_high_pass = it; option(com.brahmadeo.supertonic.tts.utils.SpeechFilters.HIGH_PASS, it) }
                        Text("Убирает низкочастотный гул и смещение нуля, из-за которых голос звучит «бубняще» и щёлкает. Речь ниже 70 Гц почти не звучит. Нагрузка: 5 умножений на отсчёт — доли процента от работы синтеза, на батарее незаметно.", style = MaterialTheme.typography.bodySmall)
                        var f_limiter by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.SpeechFilters.enabled(this@LlmSettingsActivity, com.brahmadeo.supertonic.tts.utils.SpeechFilters.LIMITER)) }
                        Toggle("Мягкий ограничитель", f_limiter) { f_limiter = it; option(com.brahmadeo.supertonic.tts.utils.SpeechFilters.LIMITER, it) }
                        Text("Громкие пики плавно скругляются вместо жёсткой обрезки — без хрипа на ударных слогах (чаще у Tera). Тише 80% шкалы звук не меняется. Нагрузка: незаметная, одна функция на громкий отсчёт.", style = MaterialTheme.typography.bodySmall)
                        var f_gate by remember { mutableStateOf(com.brahmadeo.supertonic.tts.utils.SpeechFilters.enabled(this@LlmSettingsActivity, com.brahmadeo.supertonic.tts.utils.SpeechFilters.GATE)) }
                        Toggle("Тишина в паузах · эксперимент", f_gate) { f_gate = it; option(com.brahmadeo.supertonic.tts.utils.SpeechFilters.GATE, it) }
                        Text("Приглушает шипение и шум в паузах между словами на 15 дБ, речь не трогает. Может слегка смягчить очень тихие окончания слов — поэтому выключен по умолчанию. Нагрузка: незаметная, несколько операций на отсчёт.", style = MaterialTheme.typography.bodySmall)
                        Text("Все четыре фильтра вместе — доли процента от вычислений самой модели (замер: минута звука обрабатывается примерно за 25 мс на компьютере, на телефоне — в несколько раз дольше, всё равно меньше секунды): на заряд батареи они заметно не влияют. Шипение и «плёночный» тембр, заложенные в самой модели (кодек Shtorm, сжатие Kokoro Q8, быстрый вокодер Tera), фильтром не убрать — для этого выберите полный пакет модели на главном экране (Kokoro: полная точность, Tera: Teacher).", style = MaterialTheme.typography.bodySmall)
                        }
                        if (section == Section.BUFFER) {
                        if (com.brahmadeo.supertonic.tts.utils.AssetManager.isRussianModel(this@LlmSettingsActivity)) {
                            Toggle("Готовить текст читалки заранее", readerAhead) {
                                readerAhead = it; pausePrefs.edit().putBoolean("reader_early_prepare",it).apply()
                            }
                            Choice("Кэш готового аудио в RAM", "$pcmCacheMb МБ", listOf("64 МБ","128 МБ","256 МБ","512 МБ","1024 МБ")) {
                                pcmCacheMb=it.substringBefore(' ').toInt();pausePrefs.edit().putInt("reader_pcm_cache_mb",pcmCacheMb).apply()
                            }
                            Choice("Запас готового звука вперёд", "$aheadMinutes мин", listOf("2 мин","5 мин","10 мин","20 мин","60 мин")) {
                                aheadMinutes=it.substringBefore(' ').toInt();pausePrefs.edit().putInt(SupertonicTTS.AHEAD_MINUTES_KEY,aheadMinutes).apply()
                            }
                            Text("Сколько звука заранее синтезировать сверх текущего места. Меньше — экономнее: при остановке или переходе по книге заготовленное не пропадает зря. 5 минут с запасом скрывают задержки LLM и синтеза.", style = MaterialTheme.typography.bodySmall)
                            val effectiveCacheMb = com.brahmadeo.supertonic.tts.utils.SpeechCacheBudget.limit(pcmCacheMb,Runtime.getRuntime().maxMemory())/(1024*1024)
                            if (effectiveCacheMb < pcmCacheMb) Text("На этом устройстве кэш ограничен до $effectiveCacheMb МБ, чтобы оставить память для синтеза и воспроизведения.", style = MaterialTheme.typography.bodySmall)
                            Text("Подготавливаются только фрагменты, уже поставленные читалкой в очередь. Пауза считается минимальной: тишина модели засчитывается.", style = MaterialTheme.typography.bodySmall)
                        }
                        Choice("Подготовка текста вперёд в приложении", "${config.aheadChars} символов",
                            listOf("4000 символов", "8000 символов", "16000 символов", "32000 символов", "48000 символов")) {
                            config = config.copy(aheadChars = it.substringBefore(' ').toInt()); save()
                        }
                        Text("Подготовленный текст хранится в кэше RAM: до 8 млн символов вместе с исходным контекстом (около 16 МБ текста). Повторное чтение того же блока не требует LLM. Кэш очищается при изменении настроек и закрытии процесса.")
                        Text("Движущийся буфер заранее обрабатывает следующие части загруженного текста и пополняется во время чтения. Для сторонней читалки доступны только уже переданные ею фрагменты.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
                            message = "Кэш текста и аудио очищен. Уже переданный Android звук может доиграть; следующие фрагменты будут подготовлены заново."
                        }) { Text("Очистить кэш текста и аудио") }
                        }
                        if (section == Section.READERS) {
                        Text("Moon+ Reader и другие читалки, которые задают только язык, получают мультиязычную озвучку сами.")
                        var direct by remember { mutableStateOf(pausePrefs.getBoolean(LlmPreparation.DIRECT_KEY, true)) }
                        Toggle("Не ждать LLM в читалках без очереди", direct) { direct = it; option(LlmPreparation.DIRECT_KEY, it) }
                        Text("Readest и KOReader отдают текст по одному предложению и присылают следующее только после окончания предыдущего, поэтому подготовить его заранее нельзя. Включено: такие предложения читаются сразу, без пауз: ударения и ё ставят словари (по настройкам «Ударения и буква ё»), голоса по ролям определяются по ремаркам («— ответила она», «— сказал Рогожин») и персонажам подготовленной книги. Выключено: каждое предложение ждёт LLM (лучше ударения и пунктуация, но пауза 0,5–1,5 с). Moon+ ставит текст в очередь и всегда готовится через LLM.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Readest, KOReader и читалки с выбором голоса: в списке русских голосов MyTTS есть «${com.brahmadeo.supertonic.tts.foreign.MultilingualVoice.NAME}» — голос, выбранный в MyTTS, с голосами книги и персонажей, — и «${com.brahmadeo.supertonic.tts.foreign.MultilingualVoice.SINGLE}» — только выбранный голос, без голосов персонажей. Остальные языки оба читают движками из пункта «Мультиязычная озвучка», если они подключены. В Readest «${com.brahmadeo.supertonic.tts.foreign.MultilingualVoice.NAME}» первый и выбирается по умолчанию.")
                        OutlinedButton(onClick = { runCatching { startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS")) } },
                            modifier = Modifier.fillMaxWidth()) { Text("Настройки синтеза речи Android") }
                        Text("Readest использует движок по умолчанию: выберите там MyTTS.", style = MaterialTheme.typography.bodySmall)
                        }
                        if (section != Section.LLM) {
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (message.isNotEmpty()) Text(message)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
    override fun onStop() {
        if (::voicePreview.isInitialized) voicePreview.stop()
        super.onStop()
    }
    override fun onDestroy() {
        if (::voicePreview.isInitialized) voicePreview.close()
        super.onDestroy()
    }
    private fun loadModels(gemini: Boolean): List<String> {
        val value = getSharedPreferences("llm_models", MODE_PRIVATE).getString(if (gemini) "gemini" else "ollama", "[]")!!
        return runCatching { val array = org.json.JSONArray(value); (0 until array.length()).map { array.getString(it) } }.getOrDefault(emptyList())
    }
    private fun saveModels(gemini: Boolean, models: List<String>) {
        getSharedPreferences("llm_models", MODE_PRIVATE).edit().putString(if (gemini) "gemini" else "ollama", org.json.JSONArray(models).toString()).apply()
    }
}

@Composable private fun Toggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp)); Switch(checked, change)
    }
}
@Composable private fun Choice(label: String, selected: String, options: List<String>, revision: Int = 0,
                              format: (String) -> String = { it }, highlight: (String) -> Boolean = { false }, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(revision) { if (revision > 0 && options.isNotEmpty()) expanded = true }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(format(selected)) }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { option -> DropdownMenuItem(text = { Text(format(option)) },
                modifier = if (highlight(option)) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier,
                onClick = { expanded = false; change(option) }) }
        }
    }
}
