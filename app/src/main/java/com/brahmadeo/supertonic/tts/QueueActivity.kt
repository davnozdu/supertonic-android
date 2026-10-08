package com.brahmadeo.supertonic.tts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.brahmadeo.supertonic.tts.ui.QueueScreen
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import com.brahmadeo.supertonic.tts.utils.QueueManager

class QueueActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            SupertonicTheme {
                val showClearDialog = remember { mutableStateOf(false) }

                if (showClearDialog.value) {
                    AlertDialog(
                        onDismissRequest = { showClearDialog.value = false },
                        title = { Text("Очистить очередь") },
                        text = { Text("Удалить из очереди все тексты?") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    QueueManager.clear()
                                    showClearDialog.value = false
                                }
                            ) {
                                Text("Да")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearDialog.value = false }) {
                                Text("Нет")
                            }
                        }
                    )
                }

                QueueScreen(
                    onBackClick = { finish() },
                    onClearClick = { showClearDialog.value = true }
                )
            }
        }
    }
}
