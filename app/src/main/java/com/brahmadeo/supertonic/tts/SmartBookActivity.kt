package com.brahmadeo.supertonic.tts

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.books.prepare.BookPreparation
import com.brahmadeo.supertonic.tts.books.smart.SmartFolder
import com.brahmadeo.supertonic.tts.books.smart.SmartLibrary
import com.brahmadeo.supertonic.tts.books.smart.SmartMatcher
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** «Умная книга»: the switch, the books folder (read without any LLM) and the books MyTTS can retell. */
class SmartBookActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
        setContent { SupertonicTheme {
            var enabled by remember { mutableStateOf(SmartMatcher.enabled(this)) }
            var speak by remember { mutableStateOf(prefs.getBoolean("smart_book_speak", true)) }
            var questions by remember { mutableStateOf(com.brahmadeo.supertonic.tts.books.smart.SmartRecap.questions(this)) }
            var answerSpeak by remember { mutableStateOf(com.brahmadeo.supertonic.tts.books.smart.SmartRecap.answerSpoken(this)) }
            val asr = com.brahmadeo.supertonic.tts.asr.GigaAm
            var asrReady by remember { mutableStateOf(asr.ready(this)) }
            var asrProgress by remember { mutableStateOf<Float?>(null) }
            var asrError by remember { mutableStateOf("") }
            val asrScope = rememberCoroutineScope()
            var prefetch by remember { mutableStateOf(com.brahmadeo.supertonic.tts.books.smart.SmartPrefetch.enabled(this)) }
            var wifiOnly by remember { mutableStateOf(com.brahmadeo.supertonic.tts.books.smart.SmartPrefetch.wifiOnly(this)) }
            var folder by remember { mutableStateOf(SmartFolder.folder(this)) }
            var hours by remember { mutableStateOf(SmartFolder.hours(this)) }
            var unknown by remember { mutableStateOf(SmartFolder.scansUnknown(this)) }
            val scan by SmartFolder.state.collectAsState()
            val live by SmartMatcher.position.collectAsState()
            var books by remember { mutableStateOf<List<SmartLibrary.Book>>(emptyList()) }
            var summaries by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
            var source by remember { mutableStateOf("") }
            var bytes by remember { mutableStateOf(0L) }
            LaunchedEffect(scan.running, folder) {
                withContext(Dispatchers.IO) {
                    books = SmartLibrary.list(this@SmartBookActivity)
                    bytes = SmartLibrary.bytes(this@SmartBookActivity)
                    summaries = books.associate { it.key to SmartLibrary.summaries(this@SmartBookActivity, it.key).size }
                    source = runCatching { BookPreparation.source(this@SmartBookActivity).label }.getOrDefault("Облако не настроено — откройте «Модели LLM и ключи»")
                }
            }
            LaunchedEffect(Unit) { if (folder != null && !scan.running) SmartFolder.scan(this@SmartBookActivity) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) runCatching { SmartFolder.choose(this@SmartBookActivity, uri); folder = uri }
            }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Умная книга", style = MaterialTheme.typography.headlineMedium)
                    Text("Напоминает, что было раньше: «Ранее в книге» — сюжет до места, где вы остановились, «Эта глава» — " +
                        "что случилось в текущей главе, «Чем закончится» — финал, если дочитывать не хочется. Кнопки — в панели " +
                        "быстрого чтения (уведомление MyTTS). Читалка не сообщает, какая книга открыта: MyTTS узнаёт книгу и место " +
                        "по тексту, который она присылает на озвучку.", style = MaterialTheme.typography.bodyMedium)
                    Text("Всегда под рукой: плитка «Умная книга» в шторке быстрых настроек (шторка → карандаш → перетащите " +
                        "плитку) и ярлык при долгом нажатии на иконку MyTTS.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Умная книга", Modifier.weight(1f).padding(end = 12.dp))
                        Switch(enabled, { enabled = it; prefs.edit().putBoolean(SmartMatcher.PREF_ENABLED, it).apply() })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Озвучивать пересказ сразу")
                            Text("Голосом, выбранным в MyTTS. Иначе — только текст и кнопка «Прослушать».", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(speak, { speak = it; prefs.edit().putBoolean("smart_book_speak", it).apply() })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Вопросы о книге")
                            Text("Поле «Спросить о книге» в панели быстрого чтения и в окне «Умной книги» KOReader. Ответ — по " +
                                "прочитанному (без спойлеров) облачной LLM, один запрос на вопрос.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(questions, { questions = it; prefs.edit().putBoolean(com.brahmadeo.supertonic.tts.books.smart.SmartRecap.PREF_QUESTIONS, it).apply() })
                    }
                    if (questions) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Озвучивать ответ")
                            Text("Голосом, выбранным в MyTTS. Иначе ответ только показывается.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(answerSpeak, { answerSpeak = it; prefs.edit().putBoolean(com.brahmadeo.supertonic.tts.books.smart.SmartRecap.PREF_ANSWER_SPEAK, it).apply() })
                    }
                    // «Голосовой ввод»: the microphone in «Спросить о книге»; the recognition model only on request.
                    if (questions) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Голосовой ввод")
                        Text("Значок микрофона в поле «Спросить о книге». Речь распознаётся на телефоне моделью GigaAM-v3 " +
                            "(Сбер, лицензия MIT), без отправки звука в интернет; модель ≈${asr.SIZE_MB} МБ скачивается один раз. " +
                            "Микрофон включается только по нажатию.", style = MaterialTheme.typography.bodySmall)
                        val p = asrProgress
                        when {
                            p != null -> { LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()); Text("Загрузка ${(p * 100).toInt()} %", style = MaterialTheme.typography.bodySmall) }
                            asrReady -> OutlinedButton(onClick = { asr.delete(this@SmartBookActivity); asrReady = false }) { Text("Удалить модель распознавания") }
                            else -> Button(onClick = {
                                asrError = ""; asrProgress = 0f
                                asrScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { asr.download(this@SmartBookActivity) { done, total -> asrProgress = done.toFloat() / total } }
                                        asrReady = true
                                    } catch (e: Exception) { asrError = e.message?.take(160) ?: "Ошибка загрузки" }
                                    finally { asrProgress = null }
                                }
                            }) { Text("Скачать модель (≈${asr.SIZE_MB} МБ)") }
                        }
                        if (asrError.isNotEmpty()) Text(asrError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Готовить пересказ заранее")
                            Text("Только во время чтения узнанной книги: краткое содержание уже прочитанных глав, не чаще одного " +
                                "запроса в 10 минут. Тогда «Ранее в книге» — один запрос. Без чтения телефон ничего не делает.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(prefetch, { prefetch = it; prefs.edit().putBoolean(com.brahmadeo.supertonic.tts.books.smart.SmartPrefetch.PREF_ON, it).apply() })
                    }
                    if (prefetch) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Заранее — только по Wi-Fi", Modifier.weight(1f).padding(end = 12.dp))
                        Switch(wifiOnly, { wifiOnly = it; prefs.edit().putBoolean(com.brahmadeo.supertonic.tts.books.smart.SmartPrefetch.PREF_WIFI, it).apply() })
                    }
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Папка с книгами", style = MaterialTheme.typography.titleLarge)
                            Text("EPUB · FB2 · FB2.ZIP, включая вложенные папки. Книги читаются на телефоне без LLM: главы, текст и " +
                                "отпечатки предложений. Папка проверяется при открытии этого экрана, по расписанию ниже и когда читается " +
                                "незнакомая книга. Проверка идёт только во время чтения (без фоновых пробуждений) и без новых книг — это лишь список " +
                                "файлов; книга читается один раз, когда она новая или изменилась.",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Проверять папку", style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (h in SmartFolder.HOURS) FilterChip(selected = hours == h, onClick = {
                                    hours = h; prefs.edit().putInt(SmartFolder.PREF_HOURS, h).apply()
                                }, label = { Text(if (h == 0) "Вручную" else "$h ч") })
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text("Искать новую книгу сразу")
                                    Text("Если в читалке открыта книга, которой нет в списке, папка проверяется сразу — один раз за сеанс чтения.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(unknown, { unknown = it; prefs.edit().putBoolean(SmartFolder.PREF_UNKNOWN, it).apply() })
                            }
                            Text(folder?.let { "Папка: " + (it.lastPathSegment?.substringAfter(':')?.ifBlank { "корень" } ?: it.toString()) } ?: "Папка не выбрана")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !scan.running, onClick = { picker.launch(null) }) { Text(if (folder == null) "Выбрать папку" else "Другая папка") }
                                if (folder != null) OutlinedButton(enabled = !scan.running, onClick = { SmartFolder.scan(this@SmartBookActivity) }) { Text("Обновить") }
                            }
                            if (scan.running) {
                                Text(if (scan.total > 0) "Чтение книг: ${scan.done}/${scan.total} · ${scan.current}" else "Поиск книг…", style = MaterialTheme.typography.bodySmall)
                                if (scan.total > 0) LinearProgressIndicator(progress = { scan.done.toFloat() / scan.total }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                                OutlinedButton(onClick = { SmartFolder.cancel() }) { Text("Остановить") }
                            } else if (scan.message.isNotBlank()) Text(scan.message, style = MaterialTheme.typography.bodySmall)
                            if (folder != null && !scan.running) TextButton(onClick = { SmartFolder.clear(this@SmartBookActivity); folder = null }) { Text("Отключить папку") }
                        }
                    }
                    Text("Пересказ: $source, без размышления. В облако уходит текст прочитанных разделов книги (для «Чем закончится» — " +
                        "всех). Краткое содержание раздела делается один раз и сохраняется; первый пересказ длинной книги может " +
                        "занять несколько минут. Книги, подготовленные с галочкой «Умная книга», уже содержат его.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { startActivity(LlmSettingsActivity.intent(this@SmartBookActivity, LlmSettingsActivity.Section.MODELS)) }) { Text("Модели LLM и ключи") }
                    HorizontalDivider()
                    val saved = remember { SmartMatcher.last(this@SmartBookActivity) }
                    val reading = live ?: saved
                    Text(if (books.isEmpty()) "Книг пока нет." else "Книг: ${books.size}", style = MaterialTheme.typography.titleMedium)
                    Text("Занято: %.1f МБ. Книга, удалённая из папки или из «Книг с голосами персонажей», удаляется отсюда вместе с текстом и кратким содержанием."
                        .format(bytes / 1048576.0), style = MaterialTheme.typography.bodySmall)
                    for (b in books) {
                        val now = reading?.key == b.key
                        Column {
                            Text((if (now) "▶ " else "") + b.title, style = MaterialTheme.typography.bodyLarge)
                            Text(listOf(b.author, if (b.source == SmartLibrary.PREPARED) "подготовлена" else "из папки",
                                "разделов ${b.sections}", "кратких содержаний ${summaries[b.key] ?: 0}").filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } }
    }

    companion object { fun intent(context: android.content.Context) = Intent(context, SmartBookActivity::class.java) }
}
