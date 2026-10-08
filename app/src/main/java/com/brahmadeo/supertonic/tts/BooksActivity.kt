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
                        "Подготовка может занять 10–15 минут и продолжится при выключенном экране.") },
                    confirmButton = { TextButton(onClick = {
                        val uri = pendingUri!!; val source = pendingSource!!
                        pendingUri = null; pendingSource = null
                        try { if (!BookPreparation.start(this@BooksActivity, uri, source)) message = "Подготовка другой книги уже идёт." }
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
                                TextButton(enabled = !preparation.running, onClick = { startActivity(Intent(this@BooksActivity, LlmSettingsActivity::class.java)) }) {
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
                                Text("Обработка может занять 10–15 минут. Продолжается в фоне и при выключенном экране. " +
                                    "Запросы к LLM выполняются строго по одному.", style = MaterialTheme.typography.bodySmall)
                                if (preparation.running) {
                                    Text(preparation.stage)
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
                        Text("Файл подготовленной книги (.mytts-book) содержит персонажей, их имена и пол по разделам и " +
                            "отпечатки предложений — без текста книги. Им можно делиться. Во время чтения в любой читалке " +
                            "MyTTS узнаёт книгу и раздел по тексту и включает набор персонажей.")
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
                                        Text(listOf(book.author, "персонажей ${book.characters}", "разделов ${book.sections}")
                                            .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (openBook == book.id) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(enabled = !busy, onClick = { share(book) }) { Text("Поделиться") }
                                            OutlinedButton(enabled = !busy, onClick = {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) { BookLibrary.remove(this@BooksActivity, book.id) }
                                                    changed(); reload(); message = "Книга «${book.title}» удалена"
                                                }
                                            }) { Text("Удалить") }
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

    /** Narrator, «прочие» and every character with its voice: the same picker and «Прослушать» as the multi-voice
     * role voices. Everything is automatic (narrator first, then characters, «прочие» from the rest); a voice chosen
     * here overrides it for this book. */
    @Composable private fun CharacterVoices(book: Long, revision: Int, preview: VoicePreview.State, changed: () -> Unit) {
        val pkg = remember(book) { runCatching { BookLibrary.get(this, book) }.getOrNull() } ?: return
        val voices = remember { AssetManager.russianVoices(this) }
        val assignments = remember(book, revision) { pkg.casts.mapIndexed { i, cast -> BookVoices.assignment(this, book, i, cast) } }
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
        VoiceRoleChoice(if (ownAuthor) "Голос автора в этой книге" else "Голос автора (автоматически)", author, voices, preview, voicePreview::toggle) {
            voicePreview.stop(); BookVoices.chooseRole(this, book, VoiceRole.AUTHOR, it); changed()
        }
        BookToggle("Голоса прочих — выбрать для этой книги", ownOthers) { on ->
            voicePreview.stop()
            BookVoices.chooseRole(this, book, VoiceRole.MALE, if (on) male else null)
            BookVoices.chooseRole(this, book, VoiceRole.FEMALE, if (on) female else null); changed()
        }
        val auto = if (ownOthers) "" else " (автоматически)"
        if (pkg.casts.size <= 1 || ownOthers) {
            VoiceRoleChoice("Прочие мужчины$auto", male, voices, preview, voicePreview::toggle) {
                voicePreview.stop(); BookVoices.chooseRole(this, book, VoiceRole.MALE, it); changed()
            }
            VoiceRoleChoice("Прочие женщины$auto", female, voices, preview, voicePreview::toggle) {
                voicePreview.stop(); BookVoices.chooseRole(this, book, VoiceRole.FEMALE, it); changed()
            }
        } else Text("Голоса прочих подбираются в каждом рассказе из оставшихся; включите переключатель, чтобы задать " +
            "одни на всю книгу.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        pkg.casts.forEachIndexed { index, cast ->
            if (cast.characters.isEmpty()) return@forEachIndexed
            val assignment = assignments[index]
            if (pkg.casts.size > 1) Text(cast.sections.mapNotNull { id -> pkg.sections.firstOrNull { it.id == id }?.title }.joinToString(", "),
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
                VoiceRoleChoice("${ch.name} · $gender · реплик ${ch.speaker}$note", own ?: fallback, voices, preview, voicePreview::toggle) { voice ->
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
        require(size <= BookPackage.MAX_BYTES) { "файл больше ${BookPackage.MAX_BYTES / 1024 / 1024} МБ" }
        val json = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("файл не читается")
        val id = BookLibrary.import(this, json)
        changed()
        return BookLibrary.get(this, id)?.title.orEmpty()
    }

    /** Paragraphs prepared before the change would keep old voices: prepared text, roles and audio are redone. */
    private fun changed() {
        BookMatcher.forgetAll()
        com.brahmadeo.supertonic.tts.books.BookVoices.clear()
        com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }

    private fun share(book: BookLibrary.Entry) {
        val json = BookLibrary.json(this, book.id) ?: return
        val dir = File(cacheDir, "books").apply { mkdirs() }
        val file = File(dir, book.title.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").take(80) + ".mytts-book")
        file.writeText(json)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Поделиться книгой"))
    }
}
