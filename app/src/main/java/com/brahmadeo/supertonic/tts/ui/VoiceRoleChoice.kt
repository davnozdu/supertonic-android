package com.brahmadeo.supertonic.tts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.llm.VoicePreview

/** Voice picker with «Прослушать» next to the choice and in the list (multi-voice roles, book characters). */
@Composable fun VoiceRoleChoice(label: String, selected: String, options: List<String>,
    preview: VoicePreview.State, listen: (String) -> Unit, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun voiceLabel(voice: String) = com.brahmadeo.supertonic.tts.tera.TeraVoices.label(voice)
    fun caption(voice: String) = if (preview.activeVoice == voice) "Остановить" else "Прослушать"
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { expanded = true }, enabled = options.isNotEmpty(), modifier = Modifier.weight(1f)) { Text(voiceLabel(selected)) }
            OutlinedButton(onClick = { listen(selected) }, enabled = selected in options) { Text(caption(selected)) }
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            options.forEach { voice -> DropdownMenuItem(
                text = { Text(voiceLabel(voice)) },
                trailingIcon = { TextButton(onClick = { listen(voice) }) { Text(caption(voice)) } },
                modifier = if (voice == selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier,
                onClick = { expanded = false; change(voice) }) }
        }
    }
}

