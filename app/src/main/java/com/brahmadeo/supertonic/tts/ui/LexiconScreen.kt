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
    lexiconEnabled: Boolean,
    onBackClick: () -> Unit,
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    onImportAccentDictClick: () -> Unit,
    onDownloadAccentDictClick: () -> Unit,
    onClearAccentDictClick: () -> Unit,
    onFallbackToggle: (Boolean) -> Unit,
    onLexiconToggle: (Boolean) -> Unit,
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
                            DropdownMenuItem(
                                text = { Text("Download accent dictionary…") },
                                onClick = {
                                    showMenu = false
                                    onDownloadAccentDictClick()
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
                text = { Text("Add Term") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (accentDictBanner != null) {
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

            // Master switch for the user lexicon. When off, none of the
            // custom replacement rules below are applied during synthesis —
            // useful for A/B testing whether a rule is doing more harm than
            // good without having to delete it.
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

            // Fallback toggle — works without a dictionary too. The hint
            // explicitly warns this is a heuristic, not a Russian grammar
            // rule, so users don't enable it expecting magic.
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

            if (rules.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No custom rules yet.\nAdd terms to fix pronunciations.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp, top = 16.dp, start = 16.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rules) { rule ->
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
