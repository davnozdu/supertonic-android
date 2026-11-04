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
            var tuned by remember { mutableStateOf<DeviceCheck.Tuning?>(null) }
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
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Что можно на этом телефоне", style = MaterialTheme.typography.titleMedium)
                    Text("Главное — оперативная память: ${f(r.ramGb)} ГБ" +
                        (if (r.npu == DeviceCheck.Npu.V81 || r.npu == DeviceCheck.Npu.V79) " и ускоритель NPU." else ", NPU нет — всё считает процессор."),
                        style = MaterialTheme.typography.bodyMedium)
                    if (r.can.isNotEmpty()) Text("✓ Подходит: ${r.can.joinToString(", ")}.", style = MaterialTheme.typography.bodyMedium)
                    if (r.slow.isNotEmpty()) Text("⚠ Можно, но медленно или греет: ${r.slow.joinToString(", ")}.", style = MaterialTheme.typography.bodyMedium)
                    if (r.cannot.isNotEmpty()) Text("✗ Не хватит памяти: ${r.cannot.joinToString(", ")}.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        scope.launch {
                            tuned = withContext(Dispatchers.IO) { DeviceCheck.optimize(this@DeviceCheckActivity) }
                            report = withContext(Dispatchers.IO) { DeviceCheck.run(this@DeviceCheckActivity) }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Подобрать всё за меня") }
                    Text("Выставит настройки под память и ускоритель этого телефона: NPU, варианты моделей, освобождение памяти. " +
                        "Голос не меняет и ничего не скачивает — что сделать вручную, подскажет ниже.", style = MaterialTheme.typography.bodySmall)
                    tuned?.let { t ->
                        if (t.changed.isEmpty()) Text("Всё уже настроено оптимально.", style = MaterialTheme.typography.bodyMedium)
                        else { Text("Изменено:", style = MaterialTheme.typography.titleSmall); t.changed.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) } }
                        if (t.manual.isNotEmpty()) { Text("Сделайте вручную:", style = MaterialTheme.typography.titleSmall); t.manual.forEach { Text("→ $it", style = MaterialTheme.typography.bodySmall) } }
                    }
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
                OutlinedButton(onClick = { report = null; scope.launch { report = withContext(Dispatchers.IO) { DeviceCheck.run(this@DeviceCheckActivity) } } },
                    modifier = Modifier.fillMaxWidth()) { Text("Проверить снова") }
            } }
        } }
    }
}
