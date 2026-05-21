package com.brahmadeo.supertonic.tts.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DebugLogPanel(modifier: Modifier = Modifier) {
    val entries = DebugLog.entries
    if (entries.isEmpty()) return

    val scroll = rememberScrollState()
    LaunchedEffect(entries.size) {
        scroll.scrollTo(scroll.maxValue)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 140.dp)
                .verticalScroll(scroll)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            entries.forEach { e ->
                val color = when (e.level) {
                    DebugLog.Level.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                    DebugLog.Level.WARN -> Color(0xFFE6A700)
                    DebugLog.Level.ERROR -> MaterialTheme.colorScheme.error
                }
                Text(
                    text = e.text,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = color,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
