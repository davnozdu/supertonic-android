package com.brahmadeo.supertonic.tts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.utils.LexiconItem
import com.brahmadeo.supertonic.tts.utils.PlaybackPrefs

data class AccentDictBanner(
    val source: String,
    val entries: Int,
    val sizeBytes: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LexiconScreen(
    rules: List<LexiconItem>,
    accentDictBanner: AccentDictBanner?,
    canDownloadAccentDict: Boolean,
    fallbackEnabled: Boolean,
    syncLoadEnabled: Boolean,
    lexiconEnabled: Boolean,
    tightQuestionExclamation: Boolean,
    strengthenIntonation: Boolean,
    tightEllipsis: Boolean,
    tightCommasAndPeriods: Boolean,
    chunkMode: PlaybackPrefs.ChunkMode,
    preRollEnabled: Boolean,
    preRollSentences: Int,
    onBackClick: () -> Unit,
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    onImportAccentDictClick: () -> Unit,
    onClearAccentDictClick: () -> Unit,
    onDownloadAccentDictTextClick: () -> Unit,
    onDownloadAccentDictBinaryClick: () -> Unit,
    onFallbackToggle: (Boolean) -> Unit,
    onSyncLoadToggle: (Boolean) -> Unit,
    onLexiconToggle: (Boolean) -> Unit,
    onTightQuestionToggle: (Boolean) -> Unit,
    onDoubleMarksToggle: (Boolean) -> Unit,
    onTightEllipsisToggle: (Boolean) -> Unit,
    onTightCommasPeriodsToggle: (Boolean) -> Unit,
    onChunkModeChange: (PlaybackPrefs.ChunkMode) -> Unit,
    onPreRollToggle: (Boolean) -> Unit,
    onPreRollSentencesChange: (Int) -> Unit,
    onAddClick: () -> Unit,
    onEditClick: (LexiconItem) -> Unit,
    onDeleteClick: (LexiconItem) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pronunciation Dictionary") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Import JSON") },
                            onClick = {
                                showMenu = false
                                onImportClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export JSON") },
                            onClick = {
                                showMenu = false
                                onExportClick()
                            }
                        )
                        HorizontalDivider()
                        if (canDownloadAccentDict) {
                            // Two separate items, format chosen up front so the
                            // chooser dialog can show only the relevant sizes.
                            // Binary is listed first because it's the better
                            // default for everyone (~10-20 MB RAM vs ~390 MB).
                            DropdownMenuItem(
                                text = { Text("Download accent dictionary (binary)…") },
                                onClick = {
                                    showMenu = false
                                    onDownloadAccentDictBinaryClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Download accent dictionary (text)…") },
                                onClick = {
                                    showMenu = false
                                    onDownloadAccentDictTextClick()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Import accent dictionary from file…") },
                            onClick = {
                                showMenu = false
                                onImportAccentDictClick()
                            }
                        )
                        // Mirror the banner's Delete button here so users can
                        // also reach it via the overflow menu, in case the
                        // banner is hidden or they're looking under "more".
                        if (accentDictBanner != null) {
                            DropdownMenuItem(
                                text = { Text("Clear accent dictionary") },
                                onClick = {
                                    showMenu = false
                                    onClearAccentDictClick()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddClick,
                icon = { Icon(Icons.Default.Add, "Add Term") },
                text = { Text("Add Term") },
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            if (accentDictBanner != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = accentDictBanner.source,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "%,d entries · %.1f MB".format(
                                        accentDictBanner.entries,
                                        accentDictBanner.sizeBytes / 1_048_576.0
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            IconButton(onClick = onClearAccentDictClick) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete accent dictionary",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Master switch for the user lexicon. When off, none of the
            // custom replacement rules below are applied during synthesis —
            // useful for A/B testing whether a rule is doing more harm than
            // good without having to delete it.
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Apply user lexicon",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "When on, the rules below rewrite text before the model sees it. Turn off to disable all custom rules at once without deleting them.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = lexiconEnabled,
                            onCheckedChange = onLexiconToggle
                        )
                    }
                }
            }

            // Fallback toggle — works without a dictionary too. The hint
            // explicitly warns this is a heuristic, not a Russian grammar
            // rule, so users don't enable it expecting magic.
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Stress fallback (Russian)",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "When a word isn't in the dictionary, fall back to the penultimate vowel (paroxytone). Common Russian suffixes (-ция, -ние, -ист) get their own special positions. Heuristic — sometimes wrong, but better than nothing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = fallbackEnabled,
                            onCheckedChange = onFallbackToggle
                        )
                    }
                }
            }

            // Sync-load toggle. Defaults OFF so the dictionary parses in the
            // background and the first sentence after a cold start may render
            // un-stressed; turning it ON makes Service.onCreate block until
            // the full 165 MB dict is loaded — slower startup, but guaranteed
            // stresses from the very first word.
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Block first synthesis until dictionary loads",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Off: dictionary parses in the background after a cold start; the very first sentence may go without stress marks. On: the service waits ~5-10 s on first launch so every sentence is stressed from the start. Recommended only if the first sentence really matters (short automation TTS, single-word readouts).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = syncLoadEnabled,
                            onCheckedChange = onSyncLoadToggle
                        )
                    }
                }
            }

            // Punctuation experiments. Each toggle is independent; default OFF
            // preserves the legacy stabilisation rules. Section header sets the
            // shared context so the four switches read as a single feature.
            item {
                Text(
                    text = "Punctuation",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                )
            }

            item {
                PunctuationToggleRow(
                    title = "Tight ?/!",
                    description = "Don't insert a space before ? and ! at the end of a chunk. Glues the mark to the preceding word so the model gets a stronger intonation hint.",
                    checked = tightQuestionExclamation,
                    onToggle = onTightQuestionToggle
                )
            }

            item {
                PunctuationToggleRow(
                    title = "Strengthen intonation",
                    description = "Double ?/! at the end of a chunk (`Куда?` → `Куда??`). Some models react with a stronger prosodic contour; others ignore it.",
                    checked = strengthenIntonation,
                    onToggle = onDoubleMarksToggle
                )
            }

            item {
                PunctuationToggleRow(
                    title = "Tight ellipsis",
                    description = "Normalize `…` and `. . .` to `...` and strip whitespace before it. One expressive pause instead of three independent dots.",
                    checked = tightEllipsis,
                    onToggle = onTightEllipsisToggle
                )
            }

            item {
                PunctuationToggleRow(
                    title = "Tight commas and periods",
                    description = "Skip the legacy `,/;` spacing stabilisation at end of chunk and the closing-quote+period split. Cleaner phrasing but slightly higher glitch risk on rare punctuation.",
                    checked = tightCommasAndPeriods,
                    onToggle = onTightCommasPeriodsToggle
                )
            }

            // ─── Playback section ────────────────────────────────────────
            // Independent of Lexicon/Punctuation — controls how text is
            // chunked before synthesis and whether AudioTrack waits for a
            // pre-roll buffer before starting playback. Both default to the
            // legacy behavior (DEFAULT chunk size, pre-roll off) so existing
            // users see no change unless they opt in.
            item {
                Text(
                    text = "Playback",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                )
            }

            item {
                ChunkModeRow(
                    chunkMode = chunkMode,
                    onChange = onChunkModeChange
                )
            }

            item {
                PreRollRow(
                    enabled = preRollEnabled,
                    sentences = preRollSentences,
                    onToggle = onPreRollToggle,
                    onSentencesChange = onPreRollSentencesChange
                )
            }

            if (rules.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No custom rules yet.\nAdd terms to fix pronunciations.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                item {
                    Text(
                        text = "Custom Rules",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                }

                items(rules) { rule ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        LexiconItemRow(
                            item = rule,
                            onEdit = { onEditClick(rule) },
                            onDelete = { onDeleteClick(rule) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Three-option chunk-size selector with description. Uses a single-choice
 * segmented button row for a compact, idiomatic Material 3 control. The
 * labels reflect typical use cases (notifications vs balanced vs books) so
 * users don't need to know what "chunk limit" means.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChunkModeRow(
    chunkMode: PlaybackPrefs.ChunkMode,
    onChange: (PlaybackPrefs.ChunkMode) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)) {
            Text(text = "Chunk size", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Small (120) — quick start for short messages. Default (300) — balanced, current behavior. Large (500) — smoother intonation, best for books. Huge (1000, experimental) — packs whole short paragraphs into one synthesis call; useful for testing Moon+ Reader-style flows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            val options = PlaybackPrefs.ChunkMode.values()
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = chunkMode == mode,
                        onClick = { onChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    ) {
                        Text(
                            text = when (mode) {
                                PlaybackPrefs.ChunkMode.SMALL -> "Small"
                                PlaybackPrefs.ChunkMode.DEFAULT -> "Default"
                                PlaybackPrefs.ChunkMode.LARGE -> "Large"
                                PlaybackPrefs.ChunkMode.HUGE -> "Huge"
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pre-roll switch with an inline slider that only appears when the switch is
 * on. Slider range matches PlaybackPrefs (1..5) and reads/writes through the
 * onSentencesChange callback so the activity owns persistence.
 */
@Composable
private fun PreRollRow(
    enabled: Boolean,
    sentences: Int,
    onToggle: (Boolean) -> Unit,
    onSentencesChange: (Int) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Pre-roll buffer", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Build up several sentences of synthesized audio in RAM (~1-3 MB) before playback starts. Smooths out pauses between sentences on weak devices. Costs a longer wait at the very beginning of playback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (enabled) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Sentences buffered before start: $sentences",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // valueRange (1, 5) with 3 steps between gives 1,2,3,4,5 stops.
                Slider(
                    value = sentences.toFloat(),
                    onValueChange = { onSentencesChange(it.toInt()) },
                    valueRange = 1f..5f,
                    steps = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Single-line toggle with title + small description, used four times for the
 * punctuation tweaks. Factored out so all four rows share spacing/colors.
 */
@Composable
private fun PunctuationToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}

@Composable
fun LexiconItemRow(
    item: LexiconItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.term,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "➜ ${item.replacement}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.isRegex) {
                        Text(
                            text = "Regex",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (!item.ignoreCase) {
                        Text(
                            text = "Case sensitive",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun LexiconEditDialog(
    item: LexiconItem?,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean, Boolean) -> Unit,
    onTest: (String) -> Unit
) {
    var term by remember { mutableStateOf(item?.term ?: "") }
    var replacement by remember { mutableStateOf(item?.replacement ?: "") }
    var ignoreCase by remember { mutableStateOf(item?.ignoreCase ?: true) }
    var isRegex by remember { mutableStateOf(item?.isRegex ?: false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Add Pronunciation Rule" else "Edit Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { Text(if (isRegex) "Regex Pattern" else "Term (e.g. LLMs)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text("Replacement (e.g. L L Ems)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Ignore Case",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = ignoreCase,
                        onCheckedChange = { ignoreCase = it }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Regex Mode",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = isRegex,
                        onCheckedChange = { isRegex = it }
                    )
                }
                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (term.isBlank() || replacement.isBlank()) {
                        error = "Fields cannot be empty"
                    } else {
                        onSave(term.trim(), replacement.trim(), ignoreCase, isRegex)
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    if (replacement.isNotBlank()) onTest(replacement) else error = "Enter replacement to test"
                }) {
                    Text("Test")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
