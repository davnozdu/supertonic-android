package com.brahmadeo.supertonic.tts.ui

import androidx.compose.animation.AnimatedVisibility
import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    placeholderText: String,
    isInitializing: Boolean,
    isSynthesizing: Boolean,
    onSynthesizeClick: () -> Unit,

    // Voice & Language
    languages: Map<String, String>,
    currentLangCode: String,
    onLangChange: (String) -> Unit,

    voices: Map<String, String>,
    selectedVoiceFile: String,
    onVoiceChange: (String) -> Unit,

    // Mixing
    isMixingEnabled: Boolean,
    onMixingEnabledChange: (Boolean) -> Unit,
    selectedVoiceFile2: String,
    onVoice2Change: (String) -> Unit,
    mixAlpha: Float,
    onMixAlphaChange: (Float) -> Unit,

    // Speed & Quality
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    steps: Int,
    onStepsChange: (Int) -> Unit,

    isAdvancedNormalizationEnabled: Boolean,
    onAdvancedNormalizationEnabledChange: (Boolean) -> Unit,

    // Menu Actions
    onResetClick: () -> Unit,
    onSavedAudioClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onQueueClick: () -> Unit,
    onLexiconClick: () -> Unit,
    onOpenEbookClick: () -> Unit,
    isV3Ready: Boolean,

    // Mini Player
    showMiniPlayer: Boolean,
    miniPlayerTitle: String,
    miniPlayerIsPlaying: Boolean,
    onMiniPlayerClick: () -> Unit,
    onMiniPlayerPlayPauseClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                    ) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_reset)) }, onClick = { showMenu = false; onResetClick() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_open_ebook)) }, onClick = { showMenu = false; onOpenEbookClick() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_saved)) }, onClick = { showMenu = false; onSavedAudioClick() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_history)) }, onClick = { showMenu = false; onHistoryClick() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_queue)) }, onClick = { showMenu = false; onQueueClick() })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_lexicon)) },
                        onClick = { showMenu = false; onLexiconClick() },
                        enabled = currentLangCode != "ko"
                    )
                    }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                    )
                    },
                    floatingActionButton = {
                    val buttonText = when {
                    isInitializing -> stringResource(R.string.initializing)
                    isSynthesizing -> stringResource(R.string.notif_synthesizing)
                    else -> stringResource(R.string.synthesize_button)
                    }
                    val isLoading = isInitializing || isSynthesizing
                    val synthesizeContentDesc = stringResource(R.string.synthesize_content_description)

                    ExtendedFloatingActionButton(
                    onClick = onSynthesizeClick,
                    text = { Text(buttonText) },
                    icon = { Icon(painterResource(android.R.drawable.ic_btn_speak_now), contentDescription = null) },
                    expanded = true,
                    containerColor = if (isLoading) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (isLoading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.semantics {
                    contentDescription = synthesizeContentDesc
                    stateDescription = buttonText
                    if (isLoading) {
                    disabled()
                    }
                    }
                    )
                    }
                    ) { paddingValues ->
                    Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                    val scrollState = rememberScrollState()

                    Column(
                    modifier = Modifier
                    .fillMaxSize()
                    .imePadding() // Shrinks the scrollable area when keyboard is up
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                    // Text Input
                    var isFocused by remember { mutableStateOf(false) }
                    var isTextExpanded by remember { mutableStateOf(true) }
                    var lineCount by remember { mutableIntStateOf(0) }

                    // Automatically collapse if text is very long and we're not focused
                    // This helps when returning from Ebook activity with large text
                    LaunchedEffect(inputText) {
                        if (inputText.length > 500 && !isFocused) {
                            isTextExpanded = false
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box {
                            // Hidden text to measure lines
                            Text(
                                text = inputText,
                                style = LocalTextStyle.current,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(0.dp)
                                    .alpha(0f),
                                onTextLayout = { lineCount = it.lineCount }
                            )

                            OutlinedTextField(
                                value = inputText,
                                onValueChange = onInputTextChange,
                                placeholder = {
                                    if (!isFocused) {
                                        Text(placeholderText)
                                    }
                                },
                                label = { Text(stringResource(R.string.input_label)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isTextExpanded) Modifier.heightIn(min = 200.dp)
                                        else Modifier.heightIn(max = 130.dp)
                                    )
                                    .onFocusChanged {
                                        isFocused = it.isFocused
                                        if (it.isFocused) isTextExpanded = true
                                    },
                                // Increased maxLines significantly to avoid internal scrolling conflict.
                                // This makes the cursor stay visible as the whole page scrolls instead.
                                maxLines = if (isTextExpanded) 100 else 5
                            )
                        }

                        if (lineCount > 5) {
                            Text(
                                text = if (isTextExpanded) "Show less" else "Show more (${lineCount - 5} more lines)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .padding(end = 8.dp)
                                    .clickable { isTextExpanded = !isTextExpanded }
                            )
                        }
                    }

                    // Controls Card
                    Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                    Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                    // Language Selector
                    DropdownSelector(
                        label = stringResource(R.string.language_label),
                        options = languages.keys.toList(),
                        selectedOption = languages.entries.find { it.value == currentLangCode }?.key ?: "English",
                        onOptionSelected = { name -> onLangChange(languages[name] ?: "en") }
                    )

                    // Voice Selector
                    DropdownSelector(
                        label = stringResource(R.string.voice_style_label),
                        options = voices.keys.toList().sorted(),
                        selectedOption = voices.entries.find { it.value == selectedVoiceFile }?.key ?: "",
                        onOptionSelected = { name -> onVoiceChange(voices[name] ?: "M1.json") }
                    )

                    // Mix Switch
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.mix_voices_label), modifier = Modifier.weight(1f))
                        Switch(checked = isMixingEnabled, onCheckedChange = onMixingEnabledChange)
                    }
                        // Mixing Controls
                        AnimatedVisibility(visible = isMixingEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                DropdownSelector(
                                    label = stringResource(R.string.voice_style_2_label),
                                    options = voices.keys.toList().sorted(),
                                    selectedOption = voices.entries.find { it.value == selectedVoiceFile2 }?.key ?: "",
                                    onOptionSelected = { name -> onVoice2Change(voices[name] ?: "M2.json") }
                                )

                                Column {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(stringResource(R.string.mix_ratio_label), style = MaterialTheme.typography.labelMedium)
                                        Text("${(mixAlpha * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                                    }
                                    Slider(
                                        value = mixAlpha,
                                        onValueChange = onMixAlphaChange,
                                        valueRange = 0f..1f,
                                        steps = 9
                                    )
                                }
                            }
                        }

                        // Speed
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.speed_label), style = MaterialTheme.typography.labelMedium)
                                Text(String.format(Locale.US, "%.2fx", speed), style = MaterialTheme.typography.labelLarge)
                            }
                            Slider(
                                value = speed,
                                onValueChange = onSpeedChange,
                                valueRange = 0.9f..1.5f,
                                steps = 11
                            )
                        }

                        // Quality
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.quality_label), style = MaterialTheme.typography.labelMedium)
                                Text("$steps steps", style = MaterialTheme.typography.labelLarge)
                            }
                            Slider(
                                value = steps.toFloat(),
                                onValueChange = { onStepsChange(it.toInt()) },
                                valueRange = 1f..10f,
                                steps = 8
                            )
                        }

                        // Advanced Normalization for Romance Languages
                        if (currentLangCode != "en" && currentLangCode != "ko") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.advanced_normalization_label))
                                    Text(
                                        stringResource(R.string.advanced_normalization_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = isAdvancedNormalizationEnabled,
                                    onCheckedChange = onAdvancedNormalizationEnabledChange
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(80.dp)) // Spacing for FAB and MiniPlayer
            }

            // Mini Player Overlay
            if (showMiniPlayer) {
                ElevatedCard(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    onClick = onMiniPlayerClick
                ) {
                    Row(
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.now_playing_title),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = miniPlayerTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = onMiniPlayerPlayPauseClick) {
                            Icon(
                                imageVector = if (miniPlayerIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (miniPlayerIsPlaying) "Pause" else "Play"
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownSelector(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
