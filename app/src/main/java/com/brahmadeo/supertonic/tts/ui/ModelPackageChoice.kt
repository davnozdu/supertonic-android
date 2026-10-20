package com.brahmadeo.supertonic.tts.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.kokoro.KokoroDownload
import com.brahmadeo.supertonic.tts.pocket.PocketDownload
import com.brahmadeo.supertonic.tts.utils.AssetManager
import kotlinx.coroutines.launch

/** The model's second package under the voice choice: Kokoro Q8 / full precision, Shtorm fast / slow.
 * The missing package downloads from here; switching clears the audio cache (pref listener). */
@Composable
fun ModelPackageChoice(model: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE) }
    val kokoro = model == AssetManager.KOKORO_MODEL
    if (!kokoro && model != AssetManager.POCKET_MODEL) return
    val key = if (kokoro) "kokoro_full_precision" else PocketDownload.SLOW_KEY
    val first = if (kokoro) "Компактный Q8" else "Живая (fast)"
    val second = if (kokoro) "Полная точность" else "Спокойная (slow)"
    var ready by remember(model) { mutableStateOf(if (kokoro) KokoroDownload.fullReady(context) else PocketDownload.slowReady(context)) }
    var enabled by remember(model) { mutableStateOf(prefs.getBoolean(key, kokoro)) }
    var busy by remember(model) { mutableStateOf(false) }
    var status by remember(model) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownSelector(
            label = if (kokoro) "Пакет Kokoro" else "Подача Shtorm",
            options = if (ready) listOf(first, second) else listOf(first),
            selectedOption = if (enabled && ready) second else first,
            onOptionSelected = { choice ->
                enabled = choice == second
                prefs.edit().putBoolean(key, enabled).apply()
            }
        )
        Text(
            if (kokoro) "Те же голоса. Q8 занимает меньше места; полная точность на ARM может считать быстрее, но требует больше памяти."
            else "Те же голоса. Живая: шире интонация, быстрые голоса могут «строчить». Спокойная: свободнее паузы, ровнее тон, глава звучит примерно на 7% дольше.",
            style = MaterialTheme.typography.bodySmall
        )
        if (!ready) OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            busy = true
            scope.launch {
                try {
                    if (kokoro) KokoroDownload.downloadFull(context) { s, _ -> scope.launch { status = s } }
                    else PocketDownload.downloadSlow(context) { s, _ -> scope.launch { status = s } }
                    ready = true; enabled = true
                    prefs.edit().putBoolean(key, true).apply()
                    status = "$second установлена"
                } catch (t: Exception) { status = "Ошибка установки: ${t.message}" }
                finally { busy = false }
            }
        }) { Text(if (kokoro) "Скачать полную точность · 622 МБ" else "Скачать спокойную подачу · ${PocketDownload.slowSizeMb(context)} МБ") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
