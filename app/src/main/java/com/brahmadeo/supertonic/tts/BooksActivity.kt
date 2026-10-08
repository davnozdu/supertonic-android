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
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Prepared books (`.mytts-book`): import, list, share, delete; shows which book reading recognised. */
class BooksActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SupertonicTheme {
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
                        Text(position?.let { p ->
                            val title = books.firstOrNull { it.id == p.book }?.title ?: "книга ${p.book}"
                            "Сейчас читается: «$title», раздел ${p.section}"
                        } ?: "Сейчас читаемая книга не узнана", style = MaterialTheme.typography.bodySmall)
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
                        }
                    }
                }
            }
        }
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
