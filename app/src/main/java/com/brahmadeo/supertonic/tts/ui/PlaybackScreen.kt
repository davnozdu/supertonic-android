package com.brahmadeo.supertonic.tts.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackScreen(
    sentences: List<String>,
    currentIndex: Int,
    isPlaying: Boolean,
    isServiceActive: Boolean,
    isExporting: Boolean,
    exportProgress: Int, // 0 to 100 or -1 if indeterminate
    onBackClick: () -> Unit,
    onItemClick: (Int) -> Unit,
    onPlayPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onExportClick: () -> Unit,
    onCancelExportClick: () -> Unit
) {
    val listState = rememberLazyListState()

    // Auto-scroll to current index
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex < sentences.size) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now Playing") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    top = 16.dp,
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 120.dp // Space for player card
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(sentences) { index, sentence ->
                    SentenceItem(
                        text = sentence,
                        isActive = index == currentIndex,
                        onClick = { onItemClick(index) }
                    )
                }
            }

            // Floating Player Card
            ElevatedCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    if (isServiceActive || isPlaying) {
                        if (!isPlaying && !isExporting) {
                            Text(
                                text = if (sentences.size > 1) "Pre-buffering for continuous play..." else "Buffering...",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp).align(Alignment.CenterHorizontally)
                            )
                        }
                        LinearProgressIndicator(
                            progress = {
                                if (sentences.isNotEmpty()) (currentIndex + 1).toFloat() / sentences.size else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isServiceActive || isPlaying) {
                            IconButton(onClick = onStopClick) {
                                Icon(Icons.Default.Close, contentDescription = "Stop")
                            }
                        }

                        FloatingActionButton(
                            onClick = onPlayPauseClick,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play"
                            )
                        }

                        if (isServiceActive || !isPlaying) {
                            IconButton(
                                onClick = onExportClick,
                                enabled = !isExporting
                            ) {
                                Icon(Icons.Default.Save, contentDescription = "Export")
                            }
                        }
                    }
                }
            }

            // Export Overlay
            if (isExporting) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Card(
                            modifier = Modifier
                                .width(280.dp)
                                .padding(16.dp),
                            shape = MaterialTheme.shapes.extraLarge
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Saving Audio...",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                if (exportProgress >= 0) {
                                    CircularProgressIndicator(progress = { exportProgress / 100f })
                                } else {
                                    CircularProgressIndicator()
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                TextButton(
                                    onClick = onCancelExportClick,
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SentenceItem(
    text: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    )
    val contentColor by animateColorAsState(
        if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    )
    val borderStroke = if (isActive) {
        null
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = borderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            style = if (isActive) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp)
        )
    }
}
