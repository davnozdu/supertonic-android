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
import com.brahmadeo.supertonic.tts.service.ReadingIsland
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme

/** Dialog activity rather than a draw-over-other-apps window: no overlay permission. */
class QuickReadActivity : ComponentActivity() {
    private val text = mutableStateOf("")
    private var pasteRequested = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text.value = savedInstanceState?.getString("text").orEmpty()
        pasteRequested = savedInstanceState == null && intent.getBooleanExtra("paste", false)
        SleepTimer.initialize(this)
        setContent { SupertonicTheme {
            var timerExpanded by remember { mutableStateOf(false) }
            var minutes by remember { mutableStateOf("30") }
            var island by remember { mutableStateOf(SleepTimer.islandEnabled(this@QuickReadActivity)) }
            var cameraIsland by remember { mutableStateOf(ReadingIsland.enabled(this@QuickReadActivity)) }
            var islandOffset by remember { mutableFloatStateOf(ReadingIsland.offset(this@QuickReadActivity).toFloat()) }
            val timerStatus by SleepTimer.status.collectAsState()
            Surface { Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Быстрое чтение · MyTTS", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = text.value, onValueChange = { text.value = it },
                    label = { Text("Текст или ссылка на статью") }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { paste() }) { Text("Вставить") }
                    Button(enabled = text.value.isNotBlank(), onClick = {
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
                TextButton(onClick = { timerExpanded = !timerExpanded }) { Text("Таймер сна · $timerStatus") }
                if (timerExpanded) {
                    if (android.os.Build.VERSION.SDK_INT >= 36) Row {
                        Checkbox(checked = island, onCheckedChange = { island = it; SleepTimer.setIsland(this@QuickReadActivity, it) })
                        Text("Остров таймера · экспериментально", modifier = Modifier.padding(top = 12.dp))
                    }
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
                Row {
                    Checkbox(checked = cameraIsland, onCheckedChange = {
                        cameraIsland = it; ReadingIsland.configure(this@QuickReadActivity, it)
                        if (it && !android.provider.Settings.canDrawOverlays(this@QuickReadActivity)) {
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:$packageName")))
                        }
                    })
                    Text("Остров вокруг камеры · экспериментально", modifier = Modifier.padding(top = 12.dp))
                }
                if (cameraIsland) {
                    Text("Сдвиг острова вниз: ${islandOffset.toInt()} dp", style = MaterialTheme.typography.bodySmall)
                    Slider(value = islandOffset, onValueChange = { islandOffset = it; ReadingIsland.configure(this@QuickReadActivity, true, it.toInt()) }, valueRange = 0f..80f)
                }
                TextButton(onClick = { finish() }) { Text("Закрыть") }
            } }
        } }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pasteRequested) { pasteRequested = false; paste() }
    }
    private fun paste() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.let { text.value = it }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("text", text.value); super.onSaveInstanceState(outState) }
}
