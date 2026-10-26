package com.brahmadeo.supertonic.tts.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.books.BookVoiceRef
import com.brahmadeo.supertonic.tts.books.VoiceGenderSettings
import com.brahmadeo.supertonic.tts.tera.TeraCustomVoices
import com.brahmadeo.supertonic.tts.tera.TeraVoiceLibrary
import com.brahmadeo.supertonic.tts.utils.AssetManager
import com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache
import kotlinx.coroutines.launch

/** Tera: the user's own voices from files (Supertonic .json or style_ttl.npy + style_dp.npy), and removing them. */
@Composable
fun TeraVoiceImport(onChanged: () -> Unit) {
    val context = LocalContext.current
    var custom by remember { mutableStateOf(TeraCustomVoices.installed(context)) }
    var missing by remember { mutableStateOf(TeraVoiceLibrary.missing(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        val message = try {
            val voice = TeraCustomVoices.import(context, uris)
            voice.gender?.let { VoiceGenderSettings.save(context, BookVoiceRef(AssetManager.TERA_MODEL, voice.name).key, it) }
            custom = TeraCustomVoices.installed(context)
            onChanged()
            "Голос добавлен: ${TeraCustomVoices.label(voice.name)}"
        } catch (e: Exception) {
            "Голос не добавлен: ${e.message ?: e.javaClass.simpleName}"
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (missing.isNotEmpty()) {
            val mb = missing.sumOf { it.ttl + it.dp } / 1_000_000.0
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                scope.launch {
                    try {
                        TeraVoiceLibrary.download(context) { s, p -> scope.launch { status = "$s · ${(p * 100).toInt()} %" } }
                        status = "Голоса Tera скачаны"
                        onChanged()
                    } catch (e: Exception) {
                        status = "Не удалось скачать: ${e.message ?: e.javaClass.simpleName}"
                    } finally {
                        missing = TeraVoiceLibrary.missing(context)
                        busy = false
                    }
                }
            }) { Text("Скачать голоса Tera · ${missing.size} шт., %.1f МБ".format(mb)) }
            Text("Дополнительные голоса, проверенные на русском тексте. В списке — «Tera+ · …».", style = MaterialTheme.typography.bodySmall)
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Импортировать свой голос")
        }
        Text(
            "Файл .json голоса Supertonic или два файла TeraTTS: style_ttl.npy и style_dp.npy (выбрать вместе). " +
                "Голос появится в списке как «Свой · …», его можно дать персонажу. Голоса из английских наборов " +
                "читают по-русски с акцентом и разной точностью — сравните на слух.",
            style = MaterialTheme.typography.bodySmall
        )
        custom.forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(TeraCustomVoices.label(name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    TeraCustomVoices.delete(context, name)
                    VoiceGenderSettings.save(context, BookVoiceRef(AssetManager.TERA_MODEL, name).key, null)
                    SpeechPreparationCache.clear()
                    custom = TeraCustomVoices.installed(context)
                    onChanged()
                }) { Text("Удалить") }
            }
        }
    }
}
