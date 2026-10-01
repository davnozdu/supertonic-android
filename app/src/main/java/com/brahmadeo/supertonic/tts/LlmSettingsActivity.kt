package com.brahmadeo.supertonic.tts

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.llm.*
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LlmSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        LlmPreparation.initialize(this)
        setContent {
            SupertonicTheme {
                var config by remember { mutableStateOf(LlmSettings.load(this)) }
                var busy by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("") }
                var ollamaModels by remember { mutableStateOf(loadModels(false)) }
                var geminiModels by remember { mutableStateOf(loadModels(true)) }
                var testText by remember { mutableStateOf("По-прежнему светло. Ты готов? Потом мы откроем окно — и станет теплее.") }
                val scope = rememberCoroutineScope()
                val downloadStatus by LocalModelDownload.status.collectAsState()
                val downloading by LocalModelDownload.downloading.collectAsState()
                fun save() {
                    try { LlmSettings.save(this, config); message = "Настройки сохранены" }
                    catch (_: Exception) { message = "Не удалось сохранить ключи в Android Keystore" }
                }
                fun refresh(gemini: Boolean) {
                    busy = true; message = "Получение актуальных моделей…"
                    val snapshot = config
                    scope.launch {
                        try {
                            val models = withContext(Dispatchers.IO) { LlmProviders.models(snapshot, gemini) }
                            if (gemini) geminiModels = models else ollamaModels = models
                            saveModels(gemini, models)
                            message = if (models.isEmpty()) "API не вернул доступных моделей" else "Получено моделей: ${models.size}. Выберите модель ниже."
                        } catch (e: Exception) { message = "Не удалось получить модели: ${e.message?.take(120)}" }
                        finally { busy = false }
                    }
                }
                Scaffold(topBar = { TopAppBar(title = { Text("Подготовка текста LLM") }, navigationIcon = {
                    TextButton(onClick = { finish() }) { Text("Назад") }
                }) }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Toggle("LLM-обработка", config.mode != LlmMode.OFF) {
                            config = config.copy(mode = if (it) LlmSettings.previousMode(this@LlmSettingsActivity) else LlmMode.OFF)
                            save()
                        }
                        Text("Ударения и пунктуация готовятся до синтеза. При ошибке или превышении времени ожидания используется обычная обработка со словарём.")
                        if (config.mode != LlmMode.OFF) {
                            Choice("Режим", config.mode.title, LlmMode.entries.filter { it != LlmMode.OFF }.map { it.title }) { title ->
                                config = config.copy(mode = LlmMode.entries.first { it.title == title }); save()
                            }
                        }
                        Toggle("Расставлять ударения", config.stress) { config = config.copy(stress = it) }
                        Toggle("Восстанавливать пунктуацию", config.punctuation) { config = config.copy(punctuation = it) }
                        Text("Текст, уже отправленный читалкой, подготавливается в фоне с контекстом до 4000 символов. Если читалка отправляет по одному фрагменту, первая подготовка каждого нового фрагмента может занять время.", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("Ollama Cloud", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(config.ollamaEndpoint, { config = config.copy(ollamaEndpoint = it) }, label = { Text("Адрес API (HTTPS)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(config.ollamaKey, { config = config.copy(ollamaKey = it) }, label = { Text("Ключ Ollama") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { refresh(false) }, enabled = !busy) { Text("Считать актуальные модели Ollama") }
                        Choice("Модель Ollama", config.ollamaModel.ifBlank { "Выберите модель" }, ollamaModels) { config = config.copy(ollamaModel = it) }
                        Toggle("Размышление в Ollama (медленнее)", config.ollamaThinking) { config = config.copy(ollamaThinking = it) }
                        Text("По умолчанию выключено. Если модель разрешает только уровни размышления, при выключении выбирается минимальный доступный уровень.", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("Gemini", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(config.geminiKey, { config = config.copy(geminiKey = it) }, label = { Text("Ключ Gemini API") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { refresh(true) }, enabled = !busy) { Text("Считать актуальные модели Gemini") }
                        Choice("Модель Gemini", config.geminiModel.ifBlank { "Выберите модель" }, geminiModels) { config = config.copy(geminiModel = it) }
                        Toggle("Размышление в Gemini (медленнее)", config.geminiThinking) { config = config.copy(geminiThinking = it) }
                        if (ThinkingPolicy.gemini(config.geminiModel, false)?.minimumOnly == true) {
                            Text("У этой модели API не позволяет полностью отключить размышление. Выключенный тумблер устанавливает минимальный уровень.", style = MaterialTheme.typography.bodySmall)
                        } else if (config.geminiModel.isNotBlank() && ThinkingPolicy.gemini(config.geminiModel, false) == null) {
                            Text("Для этой модели API не предоставляет известной настройки размышления; тумблер к ней не применяется.", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("API может вернуть также модели звука и изображений. Для подготовки текста выберите текстовую LLM.", style = MaterialTheme.typography.bodySmall)
                        Toggle("В авторежиме сначала Gemini", config.preferGemini) { config = config.copy(preferGemini = it) }
                        Text("Авто пробует настроенные облака по порядку, затем скачанную Gemma 4. Ручной выбор облака имеет приоритет; при его сбое используется Gemma 4. Автономный режим никогда не обращается к облакам.", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("Локальная Gemma 4 E2B", style = MaterialTheme.typography.titleLarge)
                        Text(if (LocalModelDownload.ready(this@LlmSettingsActivity)) "Модель установлена и проверена" else downloadStatus)
                        Text("Скачивание из Hugging Face: 2,59 ГБ. После установки модель работает без сети и без ключей. Скачанный файл остаётся на устройстве; из RAM модель выгружается после простоя.", style = MaterialTheme.typography.bodySmall)
                        if (downloading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            OutlinedButton(onClick = { LocalModelDownload.cancel(this@LlmSettingsActivity) }) { Text("Приостановить скачивание") }
                        } else if (!LocalModelDownload.ready(this@LlmSettingsActivity)) {
                            Button(enabled = LocalModelDownload.supported(), onClick = { LocalModelDownload.start(this@LlmSettingsActivity) }) { Text("Поставить / скачать локальную модель") }
                            if (!LocalModelDownload.supported()) Text("Для локальной Gemma 4 требуется 64-битный Android")
                        }
                        Toggle("GPU для Gemma 4 (при ошибке — CPU)", config.gpu) { config = config.copy(gpu = it) }
                        Toggle("Размышление в локальной Gemma 4 (медленнее)", config.localThinking) { config = config.copy(localThinking = it) }
                        Choice("Выгрузка из RAM после простоя", "${config.idleSeconds} секунд", listOf("30 секунд", "60 секунд", "120 секунд", "300 секунд", "600 секунд")) {
                            config = config.copy(idleSeconds = it.substringBefore(' ').toInt())
                        }
                        OutlinedButton(onClick = {
                            scope.launch { withContext(Dispatchers.IO) { LlmProviders.unload() }; message = "Локальная модель выгружена из RAM" }
                        }) { Text("Выгрузить из памяти сейчас") }
                        Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить настройки") }
                        Text("Ключи шифруются Android Keystore. При выборе облака текст передаётся выбранному провайдеру. Проверка ниже обрабатывает только введённый отрывок и не воспроизводит звук.", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        OutlinedTextField(testText, { testText = it.take(2000) }, label = { Text("Отрывок для проверки") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                        Button(enabled = !busy && config.mode != LlmMode.OFF, onClick = {
                            busy = true; message = "Обработка отрывка…"
                            val snapshot = config; val sample = testText
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { LlmPreparation.test(this@LlmSettingsActivity, snapshot, sample) }
                                    message = "${result.provider}, ${result.elapsedMs} мс${if (result.fallback) " — резервная обработка; LLM не сработала" else ""}\n\n${result.text}"
                                } catch (_: Exception) { message = "Проверка не завершилась; проверьте модель, ключ и сеть" }
                                finally { busy = false }
                            }
                        }) { Text("Проверить без озвучивания") }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (message.isNotEmpty()) Text(message)
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
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
@Composable private fun Choice(label: String, selected: String, options: List<String>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(selected) }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; change(option) }) }
        }
    }
}
