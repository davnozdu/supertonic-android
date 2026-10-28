package com.brahmadeo.supertonic.tts

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.brahmadeo.supertonic.tts.books.BookLibrary
import com.brahmadeo.supertonic.tts.books.BookMatcher
import com.brahmadeo.supertonic.tts.books.BookPackage
import com.brahmadeo.supertonic.tts.books.BookVoices
import com.brahmadeo.supertonic.tts.books.BookVoiceRef
import com.brahmadeo.supertonic.tts.books.BookVoiceCatalog
import com.brahmadeo.supertonic.tts.books.prepare.BookPreparation
import com.brahmadeo.supertonic.tts.llm.VoicePreview
import com.brahmadeo.supertonic.tts.llm.VoiceRole
import com.brahmadeo.supertonic.tts.ui.VoiceRoleChoice
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import com.brahmadeo.supertonic.tts.utils.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Prepared books (`.mytts-book`): import, list, share, delete; shows which book reading recognised. */
class BooksActivity : ComponentActivity() {
    private lateinit var voicePreview: VoicePreview

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        voicePreview = VoicePreview(this)
        setContent {
            SupertonicTheme {
                val previewState by voicePreview.state.collectAsState()
                var openBook by remember { mutableStateOf<Long?>(null) }
                var voiceRevision by remember { mutableIntStateOf(0) }
                var books by remember { mutableStateOf(emptyList<BookLibrary.Entry>()) }
                var busy by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("") }
                val position by BookMatcher.recognised.collectAsState()
                val scope = rememberCoroutineScope()
                val preparation by BookPreparation.state.collectAsState()
                var elapsedSeconds by remember { mutableLongStateOf(0L) }
                LaunchedEffect(preparation.running, preparation.startedAt) {
                    while (preparation.running) {
                        elapsedSeconds = ((System.currentTimeMillis() - preparation.startedAt) / 1000).coerceAtLeast(0)
                        kotlinx.coroutines.delay(1000)
                    }
                }
                val prepPrefs = remember { getSharedPreferences("book_preparation", MODE_PRIVATE) }
                var thinking by remember { mutableStateOf(prepPrefs.getBoolean("thinking", true)) }
                var repliesOn by remember { mutableStateOf(prepPrefs.getBoolean("replies", true)) }
                var smartOn by remember { mutableStateOf(prepPrefs.getBoolean("smart", false)) }
                var scenesOn by remember { mutableStateOf(prepPrefs.getBoolean("scenes", false)) }
                val ambience by com.brahmadeo.supertonic.tts.ambience.AmbienceGeneration.state.collectAsState()
                var extractBook by remember { mutableStateOf<BookLibrary.Entry?>(null) }
                val extractPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                    val book = extractBook; extractBook = null
                    if (uri != null && book != null) scope.launch {
                        message = try {
                            withContext(Dispatchers.IO) {
                                val sha = requireNotNull(BookLibrary.contentSha(this@BooksActivity, book.id))
                                val file = requireNotNull(com.brahmadeo.supertonic.tts.books.BookFiles.source(this@BooksActivity, sha)) { "в книге нет исходного файла" }
                                contentResolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("не удалось записать")
                            }
                            "Файл книги сохранён: откройте его в читалке"
                        } catch (t: Exception) { "Не удалось извлечь файл книги: ${t.message.orEmpty()}" }
                    }
                }
                var repliesBook by remember { mutableStateOf<Long?>(null) }
                val repliesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    val id = repliesBook; repliesBook = null
                    if (uri != null && id != null) scope.launch {
                        try {
                            val source = withContext(Dispatchers.IO) { BookPreparation.source(this@BooksActivity) }
                            if (!BookPreparation.start(this@BooksActivity, uri, source, replies = true, repliesFor = id)) message = "Подготовка другой книги уже идёт."
                            else allowBackground()
                        } catch (_: Exception) { message = "Для разметки реплик настройте облачную модель и ключ в настройках LLM." }
                    }
                }
                var sourceLabel by remember { mutableStateOf("") }
                var pendingUri by remember { mutableStateOf<Uri?>(null) }
                var pendingSource by remember { mutableStateOf<BookPreparation.Source?>(null) }
                suspend fun reload() { books = withContext(Dispatchers.IO) { BookLibrary.list(this@BooksActivity) } }
                LaunchedEffect(preparation.bookId) { reload() }
                LaunchedEffect(Unit) {
                    sourceLabel = withContext(Dispatchers.IO) { runCatching { BookPreparation.source(this@BooksActivity).label }
                        .getOrDefault("Облако не настроено — откройте настройки LLM") }
                }
                val epubPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) scope.launch {
                        try {
                            pendingSource = withContext(Dispatchers.IO) { BookPreparation.source(this@BooksActivity).withThinking(thinking) }
                            sourceLabel = pendingSource!!.label
                            pendingUri = uri
                        } catch (_: Exception) { message = "Для подготовки книги настройте облачную модель и ключ в настройках LLM." }
                    }
                }
                if (pendingUri != null && pendingSource != null) AlertDialog(
                    onDismissRequest = { pendingUri = null; pendingSource = null },
                    title = { Text("Подготовить книгу") },
                    text = { Text("${pendingSource!!.label}\n\nВ облако уйдёт список кандидатов с короткими примерами, а не вся книга. " +
                        "Режим размышления ${if (pendingSource!!.thinking) "включён" else "выключен"}. " +
                        (if (repliesOn) "Затем реплики будут размечены по голосам (абзацы с речью уходят в облако). " else "") +
                        (if (smartOn) "Умная книга: текст каждого раздела уйдёт в облако для краткого содержания, текст книги сохранится в файле. " else "") +
                        (if (scenesOn) "Локации: весь текст книги уйдёт в облако по частям, чтобы определить места действия; затем на телефоне будут созданы их звуки. " else "") +
                        "Подготовка может занять 10–15 минут и продолжится при выключенном экране.") },
                    confirmButton = { TextButton(onClick = {
                        val uri = pendingUri!!; val source = pendingSource!!
                        pendingUri = null; pendingSource = null
                        try { if (!BookPreparation.start(this@BooksActivity, uri, source, replies = repliesOn, smart = smartOn, scenes = scenesOn)) message = "Подготовка другой книги уже идёт." else allowBackground() }
                        catch (_: Exception) { message = "Не удалось запустить подготовку. Выберите книгу снова." }
                    }) { Text("Подготовить") } },
                    dismissButton = { TextButton(onClick = { pendingUri = null; pendingSource = null }) { Text("Отмена") } }
                )
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        busy = true; message = "Загрузка книги…"
                        scope.launch {
                            message = try {
                                val title = withContext(Dispatchers.IO) { import(uri) }
                                reload()
                                "Книга «$title» добавлена. Откройте её в читалке — MyTTS узнает её по тексту."
                            } catch (t: Exception) { "Не удалось загрузить файл: ${t.message.orEmpty()}" }
                            busy = false
                        }
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Книги с голосами персонажей", style = MaterialTheme.typography.headlineMedium)
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Подготовить книгу", style = MaterialTheme.typography.titleLarge)
                                Text("EPUB · FB2 · FB2.ZIP", style = MaterialTheme.typography.bodySmall)
                                Text("Выберите EPUB или FB2 — MyTTS найдёт персонажей и подготовит файл голосов для чтения.")
                                if (sourceLabel.isNotBlank()) Text(sourceLabel, style = MaterialTheme.typography.bodySmall)
                                TextButton(enabled = !preparation.running, onClick = { startActivity(LlmSettingsActivity.intent(this@BooksActivity, LlmSettingsActivity.Section.MODELS)) }) {
                                    Text("Настройки LLM")
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("Режим размышления")
                                        Text("Даёт более точный результат, но работает медленнее.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(checked = thinking, enabled = !preparation.running, onCheckedChange = {
                                        thinking = it; prepPrefs.edit().putBoolean("thinking", it).apply()
                                    })
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("Разметить реплики")
                                        Text("Кто говорит каждую реплику — записывается в файл книги. Голоса персонажей в любой читалке сразу, без LLM при чтении. Добавляет около 10 минут на роман.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(checked = repliesOn, enabled = !preparation.running, onCheckedChange = {
                                        repliesOn = it; prepPrefs.edit().putBoolean("replies", it).apply()
                                    })
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("Умная книга")
                                        Text("Сохраняет текст книги в файле и делает краткое содержание каждого раздела. В панели быстрого " +
                                            "чтения появятся «Ранее в книге», «Эта глава» и «Чем закончится». Добавляет по запросу на раздел " +
                                            "(около 5–10 с каждый); файл книги станет больше на размер её текста.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(checked = smartOn, enabled = !preparation.running, onCheckedChange = {
                                        smartOn = it; prepPrefs.edit().putBoolean("smart", it).apply()
                                    })
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("Локации и фоновые звуки · эксперимент")
                                        Text("Нейросеть размечает места действия (вагон, трактир, улица), телефон создаёт для каждого 2–3 звука " +
                                            "по 30 с с одинаковой громкостью. Звуки хранятся в файле книги. Весь текст уходит в облако по частям " +
                                            "(около 1 запроса на 6 000 знаков); звуки — около 3 с на 30 с звука с NPU, 6–8 с без него. " +
                                            "Нужен генератор из раздела «Фоновая музыка».", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(checked = scenesOn, enabled = !preparation.running, onCheckedChange = {
                                        scenesOn = it; prepPrefs.edit().putBoolean("scenes", it).apply()
                                    })
                                }
                                Text("Обработка может занять 10–15 минут. Продолжается в фоне и при выключенном экране. " +
                                    "Запросы к LLM выполняются строго по одному.", style = MaterialTheme.typography.bodySmall)
                                if (preparation.running) {
                                    // Read with elapsedSeconds, so it is recomposed every second.
                                    val waiting = elapsedSeconds.let { ((System.currentTimeMillis() - preparation.stageAt) / 1000).coerceAtLeast(0) }
                                    Text(preparation.stage + if (waiting >= 5) " · ${waiting / 60}:${"%02d".format(waiting % 60)}" else "")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Text("Прошло: ${elapsedSeconds / 60} мин ${elapsedSeconds % 60} с", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (preparation.total > 0) {
                                        LinearProgressIndicator(progress = { preparation.done.toFloat() / preparation.total }, modifier = Modifier.fillMaxWidth())
                                        Text("Готово разделов: ${preparation.done}/${preparation.total}", style = MaterialTheme.typography.bodySmall)
                                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                                    OutlinedButton(onClick = BookPreparation::stop) { Text("Остановить") }
                                } else Button(enabled = !busy, onClick = { epubPicker.launch(arrayOf("application/epub+zip", "application/x-fictionbook+xml", "application/xml", "text/xml", "application/zip", "*/*")) }) { Text("Выбрать книгу") }
                                if (preparation.message.isNotBlank()) Text(preparation.message)
                            }
                        }
                        Text("Файл подготовленной книги (.mytts-book) — обычный ZIP: персонажи с именами и полом по разделам, " +
                            "отпечатки предложений, исходный EPUB/FB2 и звуки локаций; текст для «Умной книги» — если она включена. Им можно делиться. " +
                            "Во время чтения в любой читалке MyTTS узнаёт книгу и раздел по тексту и включает набор персонажей.")
                        val reading = remember(position, books) { position?.let { p ->
                            val pkg = runCatching { BookLibrary.get(this@BooksActivity, p.book) }.getOrNull()
                            "Сейчас читается: «${pkg?.title ?: "книга ${p.book}"}», раздел «${pkg?.sections?.firstOrNull { it.id == p.section }?.title ?: p.section}»"
                        } ?: "Сейчас читаемая книга не узнана" }
                        Text(reading, style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Загрузить файл книги") }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (message.isNotBlank()) Text(message)
                        HorizontalDivider()
                        if (books.isEmpty()) Text("Подготовленных книг пока нет.")
                        for (book in books) {
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.fillMaxWidth().clickable {
                                        voicePreview.stop(); openBook = if (openBook == book.id) null else book.id
                                    }.padding(vertical = 6.dp)) {
                                        Text((if (openBook == book.id) "▾ " else "▸ ") + book.title, style = MaterialTheme.typography.titleMedium)
                                        val marked = remember(book.id, preparation.bookId) { runCatching { BookLibrary.replies(this@BooksActivity, book.id).size > 0 }.getOrDefault(false) }
                                        Text(listOf(book.author, "персонажей ${book.characters}", "разделов ${book.sections}", if (marked) "реплики размечены" else "")
                                            .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (openBook == book.id) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(enabled = !busy, onClick = {
                                                busy = true; message = "Упаковка книги…"
                                                scope.launch {
                                                    val file = withContext(Dispatchers.IO) { runCatching { pack(book) }.getOrNull() }
                                                    busy = false; message = ""
                                                    if (file != null) share(file) else message = "Не удалось упаковать книгу"
                                                }
                                            }) { Text("Поделиться") }
                                            OutlinedButton(enabled = !busy, onClick = {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) { BookLibrary.remove(this@BooksActivity, book.id)
                                                        com.brahmadeo.supertonic.tts.books.prepare.BookPreparation.forgetCache(this@BooksActivity) }
                                                    changed(); reload(); message = "Книга «${book.title}» удалена"
                                                }
                                            }) { Text("Удалить") }
                                        }
                                        OutlinedButton(enabled = !busy && !preparation.running, modifier = Modifier.fillMaxWidth(), onClick = {
                                            repliesBook = book.id; repliesPicker.launch(arrayOf("*/*"))
                                        }) { Text("Разметить реплики — выбрать исходный EPUB/FB2") }
                                        val sha = remember(book.id, preparation.bookId) { BookLibrary.contentSha(this@BooksActivity, book.id) }
                                        val hasSource = remember(book.id, preparation.bookId) { sha != null && com.brahmadeo.supertonic.tts.books.BookFiles.source(this@BooksActivity, sha) != null }
                                        if (hasSource) OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                            extractBook = book; extractPicker.launch(com.brahmadeo.supertonic.tts.books.BookFiles.sourceName(this@BooksActivity, sha!!))
                                        }) { Text("Извлечь файл книги") }
                                        val scenes = remember(book.id, preparation.bookId) { BookLibrary.scenes(this@BooksActivity, book.id) }
                                        if ((scenes.locations.isNotEmpty() || scenes.moods.moods.isNotEmpty()) && sha != null) {
                                            val missing = remember(book.id, preparation.bookId, ambience.running) { com.brahmadeo.supertonic.tts.ambience.AmbienceBook.missing(this@BooksActivity, sha, scenes).size }
                                            Text("Локации: ${scenes.locations.size} (${scenes.locations.take(6).joinToString(", ") { it.name }}${if (scenes.locations.size > 6) "…" else ""}). " +
                                                (if (scenes.moods.moods.isNotEmpty()) "Настроения: ${scenes.moods.moods.joinToString(", ") { it.name }}. " else "") +
                                                (if (scenes.events.events.isNotEmpty()) "Событий: ${scenes.events.events.size}. " else "") +
                                                if (missing == 0) "Звуки готовы." else "Без звуков: $missing.", style = MaterialTheme.typography.bodySmall)
                                            if (ambience.running && ambience.book == book.id) {
                                                Text("Звуки: ${ambience.done}/${ambience.total}${if (ambience.place.isNotEmpty()) " · ${ambience.place}" else ""}", style = MaterialTheme.typography.bodySmall)
                                                if (ambience.total > 0) LinearProgressIndicator(progress = { ambience.done.toFloat() / ambience.total }, modifier = Modifier.fillMaxWidth())
                                                else LinearProgressIndicator(Modifier.fillMaxWidth())
                                                OutlinedButton(onClick = com.brahmadeo.supertonic.tts.ambience.AmbienceGeneration::stop) { Text("Остановить") }
                                            } else if (missing > 0) OutlinedButton(enabled = !busy && !ambience.running && com.brahmadeo.supertonic.tts.ambience.AmbienceModel.ready(this@BooksActivity),
                                                modifier = Modifier.fillMaxWidth(), onClick = {
                                                    com.brahmadeo.supertonic.tts.ambience.AmbienceGeneration.start(this@BooksActivity, book.id); allowBackground()
                                                }) { Text("Создать фоновые звуки") }
                                            if (ambience.book == book.id && ambience.message.isNotBlank()) Text(ambience.message, style = MaterialTheme.typography.bodySmall)
                                        }
                                        CharacterVoices(book.id, voiceRevision, previewState) { voiceRevision++ }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Preparation runs for minutes with the screen off. OnePlus (Hans) freezes an app and drops its network when
     * the screen goes off unless it may run without battery restrictions — the cloud request then hangs until the
     * screen is on again. Ask once with the system dialog; reading aloud never needed it (playing audio is exempt). */
    private fun allowBackground() {
        val power = getSystemService(android.os.PowerManager::class.java)
        if (power?.isIgnoringBatteryOptimizations(packageName) != false) return
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    /** Narrator, «прочие» and every character with its voice: the same picker and «Прослушать» as the multi-voice
     * role voices. Everything is automatic (narrator first, then characters, «прочие» from the rest); a voice chosen
     * here overrides it for this book. */
    @Composable private fun CharacterVoices(book: Long, revision: Int, preview: VoicePreview.State, changed: () -> Unit) {
        val pkg = remember(book) { runCatching { BookLibrary.get(this, book) }.getOrNull() } ?: return
        val scope = rememberCoroutineScope()
        var saving by remember(book) { mutableStateOf(false) }
        var status by remember(book) { mutableStateOf("") }
        val models = remember(book, revision) { BookVoices.models(this, book) }
        val voices = remember(book, revision) { BookVoices.available(this, book) }
        Text("Модели для этой книги", style = MaterialTheme.typography.titleMedium)
        Text("Включите одну или несколько моделей. Выбор действует только для этой книги. " +
            "Голоса разных моделей можно назначать автору и персонажам.", style = MaterialTheme.typography.bodySmall)
        for ((model, title) in BookVoiceRef.models) {
            val ready = remember(model, revision) { BookVoiceCatalog.ready(this, model) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(title)
                    Text(if (ready) "${AssetManager.russianVoices(com.brahmadeo.supertonic.tts.utils.ModelContext(this@BooksActivity, model)).size} голосов" else "Модель не скачана",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (!ready) TextButton(enabled = !saving, onClick = {
                    saving = true; voicePreview.stop()
                    scope.launch {
                        try {
                            AssetManager.download(com.brahmadeo.supertonic.tts.utils.ModelContext(this@BooksActivity, model)) { text, _ -> status = text }
                            status = "$title готова"; changed()
                        } catch (_: Exception) { status = "Не удалось скачать $title. Попробуйте ещё раз." }
                        finally { saving = false }
                    }
                }) { Text("Скачать") }
                Switch(checked = model in models, enabled = ready && !saving && (model !in models || models.size > 1), onCheckedChange = { on ->
                    saving = true; voicePreview.stop()
                    scope.launch {
                        try { withContext(Dispatchers.IO) {
                            BookVoices.setModels(this@BooksActivity, book,
                                (if (on) models + model else models - model).filter { BookVoiceCatalog.ready(this@BooksActivity, it) })
                        }; changed(); status = "Модели выбраны. Нажмите «Расставить голоса», чтобы распределить их заново." }
                        catch (_: Exception) { status = "Не удалось выбрать модели. Оставьте хотя бы одну скачанную модель." }
                        finally { saving = false }
                    }
                })
            }
        }
        var showGenders by remember(book) { mutableStateOf(false) }
        OutlinedButton(onClick = { showGenders = !showGenders }) { Text(if (showGenders) "Скрыть пол голосов" else "Пол голосов — задать вручную") }
        if (showGenders) {
            Text("Можно указать пол каждого голоса во всех скачанных моделях. Этот выбор общий для книг. " +
                "«Не определён» исключает голос из автоматического распределения. После изменения нажмите «Расставить голоса».", style = MaterialTheme.typography.bodySmall)
            for ((model, title) in BookVoiceRef.models) if (BookVoiceCatalog.ready(this, model)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                for (name in AssetManager.russianVoices(com.brahmadeo.supertonic.tts.utils.ModelContext(this, model))) {
                    val ref = BookVoiceRef(model, name).key
                    var open by remember(book, ref) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(BookVoiceCatalog.label(this@BooksActivity, ref), style = MaterialTheme.typography.bodySmall)
                            Box {
                                TextButton(enabled = !saving, onClick = { open = true }) { Text("Указать пол") }
                                DropdownMenu(open, { open = false }) {
                                    for ((value, caption) in listOf("m" to "Мужской", "f" to "Женский", "?" to "Не определён", "" to "Вернуть исходный")) {
                                        DropdownMenuItem(text = { Text(caption) }, onClick = {
                                            open = false; voicePreview.stop()
                                            com.brahmadeo.supertonic.tts.books.VoiceGenderSettings.save(this@BooksActivity, ref, value.ifEmpty { null }); changed()
                                        })
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { voicePreview.toggle(ref) }) { Text(if (preview.activeVoice == ref) "Остановить" else "Прослушать") }
                    }
                }
            }
        }
        Text(BookVoiceRef.memoryCaption(models, com.brahmadeo.supertonic.tts.kokoro.KokoroDownload.fullEnabled(this)), style = MaterialTheme.typography.bodySmall)
        Button(enabled = !saving && voices.isNotEmpty(), onClick = {
            saving = true; voicePreview.stop()
            scope.launch {
                try { withContext(Dispatchers.IO) { BookVoices.redistribute(this@BooksActivity, book) }; changed(); status = "Голоса расставлены из выбранных моделей." }
                catch (_: Exception) { status = "Не удалось расставить голоса. Проверьте, что выбранные модели скачаны." }
                finally { saving = false }
            }
        }) { Text("Расставить голоса") }
        Text("Распределяет голоса персонажей заново, заменяя ручной выбор. Голос автора и выбранные голоса прочих сохраняются. " +
            "Повторная обработка книги через LLM не нужна.", style = MaterialTheme.typography.bodySmall)
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        // A novel prepared by chapters has a cast per group of chapters with the same characters: one list for it.
        val groups = pkg.groups
        val assignments = remember(book, revision) { groups.map { BookVoices.assignment(this, book, pkg, it.first()) } }
        val first = assignments.firstOrNull()
        val author = remember(book, revision) { BookVoices.author(this, book) }
        val ownAuthor = remember(book, revision) { BookVoices.ownRole(this, book, VoiceRole.AUTHOR) != null }
        val ownOthers = remember(book, revision) { listOf(VoiceRole.MALE, VoiceRole.FEMALE).any { BookVoices.ownRole(this, book, it) != null } }
        val male = BookVoices.roleVoice(this, book, VoiceRole.MALE, first)
        val female = BookVoices.roleVoice(this, book, VoiceRole.FEMALE, first)
        Text("Голоса раздаются автоматически: сначала автор (он читает больше всего текста — голос автора из настроек " +
            "мультиголоса), затем главные персонажи по полу, «прочие» получают оставшиеся голоса. Любой голос можно " +
            "сменить и прослушать.", style = MaterialTheme.typography.bodySmall)
        BookToggle("Голос автора — выбрать для этой книги", ownAuthor) { on ->
            voicePreview.stop(); BookVoices.chooseRole(this, book, VoiceRole.AUTHOR, if (on) author else null); changed()
        }
        VoiceRoleChoice(if (ownAuthor) "Голос автора в этой книге" else "Голос автора (автоматически)", author, voices, preview, voicePreview::toggle, model = AssetManager.getModelType(this)) {
            voicePreview.stop(); BookVoices.chooseRole(this, book, VoiceRole.AUTHOR, it); changed()
        }
        BookToggle("Голоса прочих — выбрать для этой книги", ownOthers) { on ->
            voicePreview.stop()
            BookVoices.chooseRole(this, book, VoiceRole.MALE, if (on) male else null)
            BookVoices.chooseRole(this, book, VoiceRole.FEMALE, if (on) female else null); changed()
        }
        val auto = if (ownOthers) "" else " (автоматически)"
        if (groups.size <= 1 || ownOthers) {
            VoiceRoleChoice("Прочие мужчины$auto", male, voices, preview, voicePreview::toggle, model = AssetManager.getModelType(this)) {
                voicePreview.stop(); BookVoices.chooseRole(this, book, VoiceRole.MALE, it); changed()
            }
            VoiceRoleChoice("Прочие женщины$auto", female, voices, preview, voicePreview::toggle, model = AssetManager.getModelType(this)) {
                voicePreview.stop(); BookVoices.chooseRole(this, book, VoiceRole.FEMALE, it); changed()
            }
        } else Text("Голоса прочих подбираются в каждом рассказе из оставшихся; включите переключатель, чтобы задать " +
            "одни на всю книгу.", style = MaterialTheme.typography.bodySmall)
        val ownFirstPerson = remember(book, revision) { BookVoices.firstPerson(this, book) }
        val globalFirstPerson = remember(book, revision) { com.brahmadeo.supertonic.tts.llm.LlmSettings.firstPerson(this) }
        BookToggle("Текст от первого лица", ownFirstPerson ?: globalFirstPerson) { on ->
            voicePreview.stop(); BookVoices.setFirstPerson(this, book, on); changed()
        }
        Text("Рассказчик говорит «я»: его собственные реплики («— сказал я») читает голос автора. " +
            (if (ownFirstPerson == null) "Сейчас — как в настройках мультиголоса." else "Выбрано для этой книги."), style = MaterialTheme.typography.bodySmall)
        if (ownFirstPerson != null) TextButton(onClick = { voicePreview.stop(); BookVoices.setFirstPerson(this, book, null); changed() }) {
            Text("Как в настройках мультиголоса")
        }
        HorizontalDivider()
        groups.indices.forEach { index ->
            val cast = pkg.groupCast(index)
            if (cast.characters.isEmpty()) return@forEach
            val assignment = assignments[index]
            if (groups.size > 1) Text(cast.sections.mapNotNull { id -> pkg.sections.firstOrNull { it.id == id }?.title }.joinToString(", "),
                style = MaterialTheme.typography.titleSmall)
            for (ch in cast.characters) {
                val own = assignment.characters[ch.id]
                val manual = remember(book, ch.id, revision) { BookVoices.manual(this, book, ch.id) != null }
                val gender = when (ch.gender) { "m" -> "мужской"; "f" -> "женский"; else -> "пол неизвестен" }
                val note = when { own == null -> " · голос прочих"; manual -> " · выбран вручную"; else -> "" }
                val fallback = when (ch.gender) {
                    "m" -> BookVoices.roleVoice(this, book, VoiceRole.MALE, assignment)
                    "f" -> BookVoices.roleVoice(this, book, VoiceRole.FEMALE, assignment)
                    else -> author
                }
                VoiceRoleChoice("${ch.name} · $gender · реплик ${ch.speaker}$note", own ?: fallback, voices, preview, voicePreview::toggle, model = AssetManager.getModelType(this)) { voice ->
                    voicePreview.stop(); BookVoices.choose(this, book, ch.id, voice); changed()
                }
                if (manual) TextButton(onClick = { voicePreview.stop(); BookVoices.choose(this, book, ch.id, null); changed() }) {
                    Text("Вернуть автоматический голос")
                }
            }
        }
        if (preview.message.isNotBlank()) Text(preview.message, style = MaterialTheme.typography.bodySmall)
    }

    @Composable private fun BookToggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, Modifier.weight(1f).padding(end = 12.dp)); Switch(checked, change)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::voicePreview.isInitialized) voicePreview.stop()
    }

    override fun onDestroy() {
        if (::voicePreview.isInitialized) voicePreview.close()
        super.onDestroy()
    }

    private fun import(uri: Uri): String {
        val size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        require(size <= com.brahmadeo.supertonic.tts.books.BookArchive.MAX_BYTES) { "файл больше ${com.brahmadeo.supertonic.tts.books.BookArchive.MAX_BYTES / 1024 / 1024} МБ" }
        val temp = File.createTempFile("book-import-", ".mytts-book", cacheDir)
        try {
            contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: error("файл не читается")
            val id = com.brahmadeo.supertonic.tts.books.BookArchive.import(this, temp)
            changed()
            return BookLibrary.get(this, id)?.title.orEmpty()
        } finally { temp.delete() }
    }

    /** Paragraphs prepared before the change would keep old voices: prepared text, roles and audio are redone. */
    private fun changed() {
        BookMatcher.forgetAll()
        com.brahmadeo.supertonic.tts.books.BookVoices.clear()
        com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }

    /** The book's .mytts-book container in cache/books (one at a time), or null. */
    private fun pack(book: BookLibrary.Entry): File? {
        val dir = File(cacheDir, "books").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, book.title.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").take(80) + ".mytts-book")
        return file.takeIf { com.brahmadeo.supertonic.tts.books.BookArchive.export(this, book.id, it) }
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Поделиться книгой"))
    }
}
