package com.brahmadeo.supertonic.tts

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.service.ReadingControls
import com.brahmadeo.supertonic.tts.service.SleepTimer
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme

/** Dialog activity rather than a draw-over-other-apps window: no overlay permission. */
class QuickReadActivity : ComponentActivity() {
    private val text = mutableStateOf("")
    private var pasteRequested = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.brahmadeo.supertonic.tts.utils.NoAutofill.apply(this)
        text.value = com.brahmadeo.supertonic.tts.utils.TextHandoff.unwrap(savedInstanceState?.getString("text")).orEmpty()
        pasteRequested = savedInstanceState == null && intent.getBooleanExtra("paste", false)
        SleepTimer.initialize(this)
        setContent { SupertonicTheme {
            var timerExpanded by remember { mutableStateOf(false) }
            var minutes by remember { mutableStateOf("30") }
            val timerStatus by SleepTimer.status.collectAsState()
            Surface { Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Быстрое чтение · MyTTS", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = text.value, onValueChange = { text.value = it },
                    label = { Text("Текст или ссылка на статью") }, modifier = Modifier.fillMaxWidth(), maxLines = 5,
                    isError = text.value.length > com.brahmadeo.supertonic.tts.article.ArticleExtractor.MAX_TEXT,
                    supportingText = { if (text.value.length > com.brahmadeo.supertonic.tts.article.ArticleExtractor.MAX_TEXT) Text("Лимит 180 000 знаков. Передайте текст частями.") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { paste() }) { Text("Вставить") }
                    Button(enabled = text.value.isNotBlank() && text.value.length <= com.brahmadeo.supertonic.tts.article.ArticleExtractor.MAX_TEXT, onClick = {
                        startActivity(Intent(this@QuickReadActivity, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.value)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }) { Text("Озвучить") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { ReadingControls.play(this@QuickReadActivity) }) { Text("Плей") }
                    OutlinedButton(onClick = { ReadingControls.pause(this@QuickReadActivity) }) { Text("Пауза") }
                    OutlinedButton(onClick = { ReadingControls.stop(this@QuickReadActivity) }) { Text("Стоп") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { ReadingControls.paragraph(this@QuickReadActivity, -1) }) { Text("← Абзац") }
                    TextButton(onClick = { ReadingControls.paragraph(this@QuickReadActivity, 1) }) { Text("Абзац →") }
                }
                SmartBookPanel()
                TextButton(onClick = { timerExpanded = !timerExpanded }) { Text("Таймер сна · $timerStatus") }
                if (timerExpanded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (time in listOf(15, 30, 60)) OutlinedButton(onClick = { SleepTimer.setMinutes(this@QuickReadActivity, time) }) { Text("$time мин.") }
                    }
                    OutlinedTextField(value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(3) },
                        label = { Text("Своё время, 1–720 минут") }, singleLine = true)
                    TextButton(enabled = (minutes.toIntOrNull() ?: 0) in 1..720, onClick = { SleepTimer.setMinutes(this@QuickReadActivity, minutes.toInt()) }) { Text("Задать время") }
                    TextButton(onClick = { SleepTimer.setAtEnd(this@QuickReadActivity) }) { Text("До конца статьи / очереди MyTTS") }
                    Text("В Moon+ Reader используйте время: конец книги не передаётся движку.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { SleepTimer.cancel(this@QuickReadActivity) }) { Text("Выключить таймер") }
                }
                TextButton(onClick = { finish() }) { Text("Закрыть") }
            } }
        } }
    }
    /** «Умная книга»: the recognised book and «Ранее в книге» / «Эта глава» / «Чем закончится». */
    @Composable private fun SmartBookPanel() {
        val smart = com.brahmadeo.supertonic.tts.books.smart.SmartMatcher
        if (!remember { smart.enabled(this) }) return
        val recap = com.brahmadeo.supertonic.tts.books.smart.SmartRecap
        val live by smart.position.collectAsState()
        val state by recap.state.collectAsState()
        val speaking by com.brahmadeo.supertonic.tts.books.smart.SmartSpeech.speaking.collectAsState()
        val service = com.brahmadeo.supertonic.tts.books.smart.SmartRecapService
        var place by remember { mutableStateOf<Pair<com.brahmadeo.supertonic.tts.books.smart.SmartMatcher.Position, String>?>(null) }
        var empty by remember { mutableStateOf(false) }
        var confirmEnding by remember { mutableStateOf(false) }
        // Opened by «Ранее в книге» of the notification: start it once the place is known.
        var fromNotification by remember { mutableStateOf(intent.getStringExtra("recap")) }
        val ctx = this
        LaunchedEffect(live) {
            place = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                empty = com.brahmadeo.supertonic.tts.books.smart.SmartLibrary.isEmpty(ctx)
                smart.last(ctx)?.let { p ->
                    val text = com.brahmadeo.supertonic.tts.books.smart.SmartLibrary.text(ctx, p.key) ?: return@let null
                    val section = text.sectionOf(p.paragraph)
                    val percent = if (text.paragraphs > 0) p.paragraph * 100 / text.paragraphs else 0
                    p to "«${text.title}» · ${text.sections[section].title.ifBlank { "раздел ${section + 1}" }} · прочитано около $percent %"
                }
            }
        }
        // The retelling is made and read by a background service: the panel may be closed, the screen off.
        fun ask(kind: com.brahmadeo.supertonic.tts.books.smart.SmartRecap.Kind) {
            val p = place?.first ?: return
            com.brahmadeo.supertonic.tts.books.smart.SmartSpeech.stop()
            ReadingControls.pause(this) // the book waits while the retelling is made and read
            recap.start(this, kind, p)
            service.follow(this, getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).getBoolean("smart_book_speak", true))
        }
        LaunchedEffect(place) {
            val kind = fromNotification ?: return@LaunchedEffect
            if (place != null) { fromNotification = null; ask(com.brahmadeo.supertonic.tts.books.smart.SmartRecap.Kind.valueOf(kind)) }
        }
        HorizontalDivider()
        Text("Умная книга", style = MaterialTheme.typography.titleMedium)
        val current = place
        if (current == null) {
            Text(if (empty) "Книг пока нет: выберите папку с книгами в «Умной книге» или подготовьте книгу с галочкой «Умная книга»."
                else "Книга не узнана. Начните чтение в читалке — MyTTS узнает книгу по тексту.", style = MaterialTheme.typography.bodySmall)
            return
        }
        Text(current.second, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !state.running, onClick = { ask(com.brahmadeo.supertonic.tts.books.smart.SmartRecap.Kind.BEFORE) }) { Text("Ранее в книге") }
            OutlinedButton(enabled = !state.running, onClick = { ask(com.brahmadeo.supertonic.tts.books.smart.SmartRecap.Kind.CHAPTER) }) { Text("Эта глава") }
        }
        OutlinedButton(enabled = !state.running, onClick = { confirmEnding = true }) { Text("Чем закончится") }
        if (confirmEnding) AlertDialog(onDismissRequest = { confirmEnding = false },
            title = { Text("Чем закончится книга") },
            text = { Text("Это спойлер: MyTTS перескажет финал книги. Продолжить?") },
            confirmButton = { TextButton(onClick = { confirmEnding = false; ask(com.brahmadeo.supertonic.tts.books.smart.SmartRecap.Kind.ENDING) }) { Text("Рассказать") } },
            dismissButton = { TextButton(onClick = { confirmEnding = false }) { Text("Отмена") } })
        if (state.running) {
            Text(state.stage + if (state.total > 0) " · ${state.done}/${state.total}" else "", style = MaterialTheme.typography.bodySmall)
            if (state.total > 0) LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = { recap.cancel() }) { Text("Остановить") }
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.result?.let { result ->
            Text(state.kind?.title.orEmpty(), style = MaterialTheme.typography.labelLarge)
            Text(com.brahmadeo.supertonic.tts.books.smart.SmartPrompts.display(result))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (speaking) OutlinedButton(onClick = { service.stop(this@QuickReadActivity) }) { Text("Стоп") }
                else OutlinedButton(onClick = { ReadingControls.pause(this@QuickReadActivity); service.listen(this@QuickReadActivity) }) { Text("Прослушать") }
                Button(onClick = { service.resume(this@QuickReadActivity) }) { Text("Продолжить чтение") }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pasteRequested) { pasteRequested = false; paste() }
    }
    private fun paste() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.let { text.value = it }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("text", com.brahmadeo.supertonic.tts.utils.TextHandoff.wrap(text.value, "quick_read")); super.onSaveInstanceState(outState) }
}
