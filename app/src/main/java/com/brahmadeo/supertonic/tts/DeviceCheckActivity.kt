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
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import com.brahmadeo.supertonic.tts.utils.DeviceCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** «Проверить мой телефон»: чип, память, NPU и что из моделей подходит этому телефону. */
class DeviceCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { SupertonicTheme {
            val scope = rememberCoroutineScope()
            var report by remember { mutableStateOf<DeviceCheck.Report?>(null) }
            var applied by remember { mutableStateOf("") }
            LaunchedEffect(Unit) { report = withContext(Dispatchers.IO) { DeviceCheck.run(this@DeviceCheckActivity) } }
            Scaffold(topBar = { TopAppBar(title = { Text("Проверить мой телефон") }, navigationIcon = {
                TextButton(onClick = { finish() }) { Text("Назад") }
            }) }) { padding -> Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val r = report
                if (r == null) { Text("Проверяю телефон…"); LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
                fun f(x: Double) = "%.1f".format(x)
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Ваш телефон", style = MaterialTheme.typography.titleMedium)
                    Text("Чип: ${r.soc} · ядер ${r.cores}, до ${f(r.maxGhz)} ГГц · Android ${r.android}")
                    Text("Память: ${f(r.ramGb)} ГБ, сейчас свободно ${f(r.availGb)} ГБ (+ ${f(r.swapGb)} ГБ подкачки)")
                    Text("Ускоритель NPU: ${r.npu.title}")
                    Text("Свободно на диске: ${f(r.freeStorageGb)} ГБ")
                    Text("Класс: ${r.tier}", style = MaterialTheme.typography.bodyMedium)
                } }
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Ваши настройки", style = MaterialTheme.typography.titleMedium)
                    r.current.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                } }
                Text("✓ — рекомендуется, ⚠ — будет медленно или греть, ✗ — не хватит памяти", style = MaterialTheme.typography.bodySmall)
                r.items.groupBy { it.section }.forEach { (section, list) ->
                    Text(section, style = MaterialTheme.typography.titleMedium)
                    list.forEach { item ->
                        Column {
                            Text("${item.verdict.mark} ${item.name}", style = MaterialTheme.typography.bodyLarge)
                            Text(item.why, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (r.tips.isNotEmpty()) {
                    Text("Советы", style = MaterialTheme.typography.titleMedium)
                    r.tips.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (r.npu == DeviceCheck.Npu.V81 || r.npu == DeviceCheck.Npu.V79) {
                    Button(onClick = {
                        val done = DeviceCheck.applyAccelerators(this@DeviceCheckActivity)
                        applied = if (done.isEmpty()) "Нечего включать." else done.joinToString("\n") { "✓ $it" }
                        scope.launch { report = withContext(Dispatchers.IO) { DeviceCheck.run(this@DeviceCheckActivity) } }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Включить ускорители NPU") }
                    Text("Включает только переключатели NPU; модели и голос не меняются, ничего не скачивается. " +
                        "Если что-то на NPU не заработает, MyTTS сама посчитает это процессором.", style = MaterialTheme.typography.bodySmall)
                    if (applied.isNotEmpty()) Text(applied, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = { report = null; scope.launch { report = withContext(Dispatchers.IO) { DeviceCheck.run(this@DeviceCheckActivity) } } },
                    modifier = Modifier.fillMaxWidth()) { Text("Проверить снова") }
            } }
        } }
    }
}
