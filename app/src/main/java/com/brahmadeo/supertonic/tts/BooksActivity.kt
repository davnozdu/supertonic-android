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
import com.brahmadeo.supertonic.tts.llm.MultiVoiceSettings
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
                suspend fun reload() { books = withContext(Dispatchers.IO) { BookLibrary.list(this@BooksActivity) } }
                LaunchedEffect(Unit) { reload() }
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
                            Text(book.title, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(book.author, "персонажей ${book.characters}", "разделов ${book.sections}").filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled = !busy, onClick = { share(book) }) { Text("Поделиться") }
                                OutlinedButton(enabled = !busy, onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { BookLibrary.remove(this@BooksActivity, book.id) }
                                        changed()
                                        reload(); message = "Книга «${book.title}» удалена"
                                    }
                                }) { Text("Удалить") }
                            }
                            OutlinedButton(enabled = !busy, onClick = { voicePreview.stop(); openBook = if (openBook == book.id) null else book.id }) {
                                Text(if (openBook == book.id) "Скрыть персонажей" else "Персонажи и голоса")
                            }
                            if (openBook == book.id) CharacterVoices(book.id, voiceRevision, previewState) { voiceRevision++ }
                        }
                    }
                }
            }
        }
    }

    /** Every character with its voice: the same picker and «Прослушать» as the multi-voice role voices. */
    @Composable private fun CharacterVoices(book: Long, revision: Int, preview: VoicePreview.State, changed: () -> Unit) {
        val pkg = remember(book) { runCatching { BookLibrary.get(this, book) }.getOrNull() } ?: return
        val voices = remember { AssetManager.russianVoices(this) }
        val male = remember(book, revision) { BookVoices.roleVoice(this, book, VoiceRole.MALE) }
        val female = remember(book, revision) { BookVoices.roleVoice(this, book, VoiceRole.FEMALE) }
        val author = remember(book, revision) { BookVoices.roleVoice(this, book, VoiceRole.AUTHOR) }
        val ownAuthor = remember(book, revision) { BookVoices.ownRole(this, book, VoiceRole.AUTHOR) != null }
        val ownOthers = remember(book, revision) { listOf(VoiceRole.MALE, VoiceRole.FEMALE).any { BookVoices.ownRole(this, book, it) != null } }
        Text("Голоса из установленной модели. Голоса автора и «прочих» персонажам не раздаются; персонаж без своего голоса " +
            "читается голосом «прочих» своего пола.", style = MaterialTheme.typography.bodySmall)
        // Narrator and «прочие» once per book (a collection of stories has one narrator), or the global ones.
        BookToggle("Голос автора — свой для этой книги", ownAuthor) { on ->
            voicePreview.stop(); BookVoices.chooseRole(this, book, VoiceRole.AUTHOR, if (on) author else null); changed()
        }
        if (ownAuthor) VoiceRoleChoice("Голос автора в этой книге", author, voices, preview, voicePreview::toggle) {
            voicePreview.stop(); BookVoices.chooseRole(this, book, VoiceRole.AUTHOR, it); changed()
        } else Text("Голос автора: общий из настроек мультиголоса", style = MaterialTheme.typography.bodySmall)
        BookToggle("Голоса прочих — свои для этой книги", ownOthers) { on ->
            voicePreview.stop()
            BookVoices.chooseRole(this, book, VoiceRole.MALE, if (on) male else null)
            BookVoices.chooseRole(this, book, VoiceRole.FEMALE, if (on) female else null); changed()
        }
        if (ownOthers) {
            VoiceRoleChoice("Прочие мужчины в этой книге", male, voices, preview, voicePreview::toggle) {
                voicePreview.stop(); BookVoices.chooseRole(this, book, VoiceRole.MALE, it); changed()
            }
            VoiceRoleChoice("Прочие женщины в этой книге", female, voices, preview, voicePreview::toggle) {
                voicePreview.stop(); BookVoices.chooseRole(this, book, VoiceRole.FEMALE, it); changed()
            }
        } else Text("Голоса прочих: общие из настроек мультиголоса", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        pkg.casts.forEachIndexed { index, cast ->
            if (cast.characters.isEmpty()) return@forEachIndexed
            if (pkg.casts.size > 1) Text(cast.sections.mapNotNull { id -> pkg.sections.firstOrNull { it.id == id }?.title }.joinToString(", "),
                style = MaterialTheme.typography.titleSmall)
            val assigned = remember(book, index, revision) { BookVoices.voices(this, book, index, cast) }
            for (ch in cast.characters) {
                val own = assigned[ch.id]
                val manual = remember(book, ch.id, revision) { BookVoices.manual(this, book, ch.id) != null }
                val gender = when (ch.gender) { "m" -> "мужской"; "f" -> "женский"; else -> "пол неизвестен" }
                val note = when { own == null -> " · голос прочих"; manual -> " · выбран вручную"; else -> "" }
                VoiceRoleChoice("${ch.name} · $gender · реплик ${ch.speaker}$note",
                    own ?: when (ch.gender) { "m" -> male; "f" -> female; else -> author }, voices, preview, voicePreview::toggle) { voice ->
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
