package com.brahmadeo.supertonic.tts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.service.SleepTimer
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme

class SleepTimerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); SleepTimer.initialize(this)
        setContent { SupertonicTheme {
            val status by SleepTimer.status.collectAsState()
            var minutes by remember { mutableStateOf("30") }
            var island by remember { mutableStateOf(SleepTimer.islandEnabled(this@SleepTimerActivity)) }
            Scaffold(topBar = { TopAppBar(title = { Text("Таймер сна") }, navigationIcon = {
                TextButton(onClick = { finish() }) { Text("Назад") }
            }) }) { padding -> Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(status, style = MaterialTheme.typography.titleMedium)
                Text("Таймер останавливает чтение и фоновую музыку. Доступен из уведомления во время чтения.")
                if (android.os.Build.VERSION.SDK_INT >= 36) Row {
                    Checkbox(checked = island, onCheckedChange = { island = it; SleepTimer.setIsland(this@SleepTimerActivity, it) })
                    Text("Остров таймера · экспериментально", modifier = Modifier.padding(top = 12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (time in listOf(15, 30, 60)) Button(onClick = { SleepTimer.setMinutes(this@SleepTimerActivity, time); finish() }) { Text("$time мин.") }
                }
                OutlinedTextField(value = minutes, onValueChange = { minutes = it.filter(Char::isDigit).take(3) },
                    label = { Text("Своё время, 1–720 минут") }, singleLine = true)
                Button(enabled = (minutes.toIntOrNull() ?: 0) in 1..720, onClick = {
                    SleepTimer.setMinutes(this@SleepTimerActivity, minutes.toInt()); finish()
                }) { Text("Включить по времени") }
                OutlinedButton(onClick = { SleepTimer.setAtEnd(this@SleepTimerActivity); finish() }) { Text("До конца статьи / очереди MyTTS") }
                Text("Этот режим относится к статьям и текстам внутри MyTTS. Moon+ Reader не сообщает движку конец книги; для чтения в нём выберите время.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { SleepTimer.cancel(this@SleepTimerActivity); finish() }) { Text("Выключить таймер") }
            } }
        } }
    }
}
