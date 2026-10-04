package com.brahmadeo.supertonic.tts.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.R as AppR
import com.brahmadeo.supertonic.tts.ui.components.WavyLinearProgressIndicator

@Composable
fun DownloadScreen(
    status: String,
    progress: Float,
    isPocketModel: Boolean = false,
    isTeraModel: Boolean = false,
    isSileroModel: Boolean = false,
    isCisModel: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {}
) {
    val message = if (isPocketModel) "Shtorm PocketTTS RU v2: готовый ONNX-пакет и локальная обработка текста. Скачиваются один раз, работают без интернета." else if (isCisModel) "29 русских голосов Silero CIS. После установки работают без интернета." else stringResource(when { isSileroModel -> AppR.string.download_intro_silero; isTeraModel -> AppR.string.download_intro_tera; else -> AppR.string.download_intro })

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isPocketModel && error == null) "Установка Shtorm PocketTTS RU" else if (isCisModel && error == null) "Установка пака Silero CIS" else stringResource(
                    when {
                        error != null -> AppR.string.download_failed
                        isSileroModel -> AppR.string.download_silero_in_progress
                        isTeraModel -> AppR.string.download_tera_in_progress
                        else -> AppR.string.download_in_progress
                    }
                ),
                style = MaterialTheme.typography.headlineMedium,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = error ?: message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            if (error == null) {
                Spacer(modifier = Modifier.height(32.dp))
                WavyLinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(stringResource(AppR.string.action_retry_download))
                }
            }
        }
    }
}
