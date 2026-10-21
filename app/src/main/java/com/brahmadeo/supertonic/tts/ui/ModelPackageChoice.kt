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
import com.brahmadeo.supertonic.tts.tera.TeraQuality
import com.brahmadeo.supertonic.tts.utils.AssetManager
import kotlinx.coroutines.launch

/** The model's second package under the voice choice: Kokoro Q8 / full precision, Shtorm fast / slow, Tera
 * distilled / teacher. The missing package downloads from here; switching clears the audio cache (pref listener). */
@Composable
fun ModelPackageChoice(model: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE) }
    val kokoro = model == AssetManager.KOKORO_MODEL
    val tera = model == AssetManager.TERA_MODEL
    if (!kokoro && !tera && model != AssetManager.POCKET_MODEL) return
    val key = when { kokoro -> "kokoro_full_precision"; tera -> "tera_teacher"; else -> PocketDownload.SLOW_KEY }
    val first = when { kokoro -> "Компактный Q8"; tera -> "Быстрая (distilled)"; else -> "Живая (fast)" }
    val second = when { kokoro -> "Полная точность"; tera -> "Teacher · качественнее"; else -> "Спокойная (slow)" }
    var ready by remember(model) { mutableStateOf(when { kokoro -> KokoroDownload.fullReady(context); tera -> TeraQuality.ready(context); else -> PocketDownload.slowReady(context) }) }
    var enabled by remember(model) { mutableStateOf(prefs.getBoolean(key, kokoro)) }
    var busy by remember(model) { mutableStateOf(false) }
    var status by remember(model) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownSelector(
            label = when { kokoro -> "Пакет Kokoro"; tera -> "Синтез Tera"; else -> "Подача Shtorm" },
            options = if (ready) listOf(first, second) else listOf(first),
            selectedOption = if (enabled && ready) second else first,
            onOptionSelected = { choice ->
                enabled = choice == second
                prefs.edit().putBoolean(key, enabled).apply()
            }
        )
        Text(
            if (kokoro) "Те же голоса. Q8 занимает меньше места; полная точность на ARM может считать быстрее, но требует больше памяти."
            else if (tera) "Те же голоса. Быстрая — по умолчанию. Teacher — полный синтез: чище и естественнее, но считает в 2–3 раза дольше и сильнее грузит процессор; сравните на слух."
            else "Те же голоса. Живая: шире интонация, быстрые голоса могут «строчить». Спокойная: свободнее паузы, ровнее тон, глава звучит примерно на 7% дольше.",
            style = MaterialTheme.typography.bodySmall
        )
        if (!ready) OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            busy = true
            scope.launch {
                try {
                    if (kokoro) KokoroDownload.downloadFull(context) { s, _ -> scope.launch { status = s } }
                    else if (tera) TeraQuality.download(context) { p -> scope.launch { status = "Скачивание Tera teacher: $p %" } }
                    else PocketDownload.downloadSlow(context) { s, _ -> scope.launch { status = s } }
                    ready = true; enabled = true
                    prefs.edit().putBoolean(key, true).apply()
                    status = "$second установлена"
                } catch (t: Exception) { status = "Ошибка установки: ${t.message ?: t.javaClass.simpleName}" }
                finally { busy = false }
            }
        }) { Text(if (kokoro) "Скачать полную точность · 622 МБ" else if (tera) "Скачать Tera teacher · 257 МБ" else "Скачать спокойную подачу · ${PocketDownload.slowSizeMb(context)} МБ") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
