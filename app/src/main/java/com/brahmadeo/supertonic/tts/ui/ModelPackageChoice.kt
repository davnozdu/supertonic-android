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
 * distilled / int8 / teacher ([TeraSamplerChoice]). The missing package downloads from here; switching clears the audio cache (pref listener). */
@Composable
fun ModelPackageChoice(model: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE) }
    val kokoro = model == AssetManager.KOKORO_MODEL
    val tera = model == AssetManager.TERA_MODEL
    if (!kokoro && !tera && model != AssetManager.POCKET_MODEL) return
    if (tera) { TeraSamplerChoice(); return }
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
                } catch (t: Exception) { status = "Ошибка установки: ${t.message ?: t.javaClass.simpleName}" }
                finally { busy = false }
            }
        }) { Text(if (kokoro) "Скачать полную точность · 622 МБ" else "Скачать спокойную подачу · ${PocketDownload.slowSizeMb(context)} МБ") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

/** Tera: distilled (default), the compact int8 build of it, or the teacher. Each extra one downloads from here. */
@Composable
private fun TeraSamplerChoice() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE) }
    val fast = "Быстрая (distilled)"; val int8 = "Сжатая int8 · экономнее"; val teacher = "Teacher · качественнее"
    var teacherReady by remember { mutableStateOf(TeraQuality.ready(context)) }
    var int8Ready by remember { mutableStateOf(TeraQuality.int8Ready(context)) }
    fun current() = when (TeraQuality.selected(context)) { TeraQuality.TEACHER -> teacher; TeraQuality.INT8 -> int8; else -> fast }
    var chosen by remember { mutableStateOf(current()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun choose(choice: String) {
        prefs.edit().putBoolean("tera_teacher", choice == teacher).putBoolean("tera_int8", choice == int8).apply()
        chosen = choice
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownSelector(
            label = "Синтез Tera",
            options = listOfNotNull(fast, int8.takeIf { int8Ready }, teacher.takeIf { teacherReady }),
            selectedOption = chosen,
            onOptionSelected = { choose(it) }
        )
        Text("Те же голоса. Быстрая — по умолчанию. Сжатая int8 — та же быстрая модель в 8 битах: 65 МБ, примерно в 3 раза меньше " +
            "нагрузки на процессор, звук чуть отличается — сравните на слух. Teacher — полный синтез: чище и естественнее, но считает " +
            "в 2–3 раза дольше и сильнее грузит процессор.", style = MaterialTheme.typography.bodySmall)
        fun download(what: String) {
            busy = true
            scope.launch {
                try {
                    if (what == int8) { TeraQuality.downloadInt8(context) { p -> scope.launch { status = "Скачивание Tera int8: $p %" } }; int8Ready = true }
                    else { TeraQuality.download(context) { p -> scope.launch { status = "Скачивание Tera teacher: $p %" } }; teacherReady = true }
                    choose(what)
                    status = "$what установлена"
                } catch (t: Exception) { status = "Ошибка установки: ${t.message ?: t.javaClass.simpleName}" }
                finally { busy = false }
            }
        }
        if (!int8Ready) OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { download(int8) }) { Text("Скачать Tera int8 · 65 МБ") }
        if (!teacherReady) OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { download(teacher) }) { Text("Скачать Tera teacher · 257 МБ") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
