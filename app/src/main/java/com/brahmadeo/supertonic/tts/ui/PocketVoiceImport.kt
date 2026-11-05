package com.brahmadeo.supertonic.tts.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
import com.brahmadeo.supertonic.tts.pocket.PocketCustomVoices
import com.brahmadeo.supertonic.tts.pocket.PocketVoicePrep
import com.brahmadeo.supertonic.tts.tera.TeraCustomVoices
import com.brahmadeo.supertonic.tts.utils.AssetManager
import com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shtorm: the user's own voice from a speech recording (cleaned, a piece chosen and heard before saving), and removing it. */
@Composable
fun PocketVoiceImport(onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var custom by remember { mutableStateOf(PocketCustomVoices.installed(context)) }
    var busy by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<PocketCustomVoices.Draft?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                draft = withContext(Dispatchers.Default) { PocketCustomVoices.analyse(context, uri) }
            } catch (e: Exception) {
                Toast.makeText(context, "Запись не подходит: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Обработка записи…" else "Свой голос из записи")
        }
        Text(
            "Запись речи (wav, mp3, m4a, ogg…): 6–10 секунд одного голоса без музыки, в тихой комнате, обычным диктофоном; " +
                "читайте ровно, как книгу — Shtorm переносит тембр, темп и манеру записи. Программа сама убирает гул и долгие " +
                "заминки, выравнивает громкость и берёт самый чистый кусок. Голос появится в списке как «Свой · …», его можно дать персонажу.",
            style = MaterialTheme.typography.bodySmall
        )
        custom.forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(TeraCustomVoices.label(name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    PocketCustomVoices.delete(context, name)
                    VoiceGenderSettings.save(context, BookVoiceRef(AssetManager.POCKET_MODEL, name).key, null)
                    SpeechPreparationCache.clear()
                    custom = PocketCustomVoices.installed(context)
                    onChanged()
                }) { Text("Удалить") }
            }
        }
    }
    draft?.let { d ->
        PocketVoiceDialog(d, onDismiss = { draft = null }, onSaved = { name ->
            draft = null
            custom = PocketCustomVoices.installed(context)
            onChanged()
            Toast.makeText(context, "Голос добавлен: ${TeraCustomVoices.label(name)}", Toast.LENGTH_LONG).show()
        })
    }
}

@Composable
private fun PocketVoiceDialog(draft: PocketCustomVoices.Draft, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val parts = linkedMapOf(PocketVoicePrep.PART_AUTO to "Лучший", PocketVoicePrep.PART_START to "Начало",
        PocketVoicePrep.PART_MIDDLE to "Середина", PocketVoicePrep.PART_END to "Конец")
    val genders = linkedMapOf<String?, String>(null to "не задан", "m" to "мужской", "f" to "женский")
    var part by remember { mutableStateOf(PocketVoicePrep.PART_AUTO) }
    var gender by remember { mutableStateOf<String?>(null) }
    val piece = remember(part) { runCatching { PocketCustomVoices.piece(draft, part) } }
    var track by remember { mutableStateOf<AudioTrack?>(null) }
    fun stop() { track?.let { runCatching { it.stop() }; it.release() }; track = null }
    DisposableEffect(Unit) { onDispose { stop() } }
    LaunchedEffect(part) { stop() }
    AlertDialog(
        onDismissRequest = { stop(); onDismiss() },
        title = { Text("Свой голос Shtorm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownSelector(label = "Кусок записи", options = parts.values.toList(), selectedOption = parts.getValue(part),
                    onOptionSelected = { c -> part = parts.entries.first { it.value == c }.key })
                piece.fold(
                    onSuccess = { clip -> Text("Кусок %.1f с. Гул и долгие заминки убраны, громкость выровнена.".format(PocketVoicePrep.seconds(clip)),
                        style = MaterialTheme.typography.bodySmall) },
                    onFailure = { e -> Text("Этот кусок не подходит: ${e.message}", style = MaterialTheme.typography.bodySmall) }
                )
                PocketCustomVoices.notes(draft, piece.getOrNull()).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                piece.getOrNull()?.let { clip ->
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        if (track != null) { stop(); return@OutlinedButton }
                        val pcm = PocketCustomVoices.pcm16(clip)
                        track = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(AudioFormat.Builder().setSampleRate(PocketVoicePrep.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size).build()
                            .also { it.write(pcm, 0, pcm.size); it.play() }
                    }) { Text(if (track != null) "Остановить" else "Послушать кусок") }
                }
                DropdownSelector(label = "Пол голоса (для ролей и персонажей)", options = genders.values.toList(), selectedOption = genders.getValue(gender),
                    onOptionSelected = { c -> gender = genders.entries.first { it.value == c }.key })
                Text("Как голос читает текст, проверьте после сохранения: выберите его в списке голосов и прочитайте фразу.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = piece.isSuccess, onClick = {
                stop()
                try {
                    val name = PocketCustomVoices.save(context, draft, piece.getOrThrow())
                    gender?.let { VoiceGenderSettings.save(context, BookVoiceRef(AssetManager.POCKET_MODEL, name).key, it) }
                    onSaved(name)
                } catch (e: Exception) {
                    Toast.makeText(context, "Голос не сохранён: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = { stop(); onDismiss() }) { Text("Отмена") } }
    )
}
