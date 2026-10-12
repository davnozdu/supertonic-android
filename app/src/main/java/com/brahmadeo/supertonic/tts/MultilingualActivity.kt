package com.brahmadeo.supertonic.tts

import android.content.Intent
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
import com.brahmadeo.supertonic.tts.foreign.ForeignTts
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme

/** «Мультиязычная озвучка»: one place for the external engine that reads non-Russian text, shared by every model
 * and every reader (Moon+ by language, Readest through the «Мультиязычный» voice). */
class MultilingualActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SupertonicTheme {
                val prefs = remember { getSharedPreferences("SupertonicPrefs", MODE_PRIVATE) }
                val engines = remember { ForeignTts.engines(this) }
                var enabled by remember { mutableStateOf(ForeignTts.enabled(this)) }
                var engine by remember { mutableStateOf(prefs.getString("foreign_engine", "") ?: "") }
                var language by remember { mutableStateOf(prefs.getString("foreign_language", "auto") ?: "auto") }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Мультиязычная озвучка", style = MaterialTheme.typography.headlineMedium)
                        Text("Русский текст читает выбранная модель MyTTS, фрагменты на других языках — другой установленный движок Android TTS. Настройки общие для всех моделей и читалок.")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Иностранные фрагменты через другой Android TTS", Modifier.weight(1f).padding(end = 12.dp))
                            Switch(enabled, { enabled = it; prefs.edit().putBoolean("foreign_tts", it).apply(); ForeignTts.reset() })
                        }
                        val engineLabels = listOf("Системный движок Android") + engines.map { "${it.title} (${it.id})" }
                        val engineIndex = engines.indexOfFirst { it.id == engine } + 1
                        Choice("Движок для иностранных фрагментов", engineLabels.getOrElse(engineIndex) { engineLabels[0] }, engineLabels) { value ->
                            val index = engineLabels.indexOf(value)
                            engine = if (index <= 0) "" else engines[index - 1].id
                            prefs.edit().putString("foreign_engine", engine).apply(); ForeignTts.reset()
                        }
                        val languageLabels = listOf("Автоматически", "Английский", "Чешский")
                        val languageIds = listOf("auto", "en", "cs")
                        Choice("Язык латинских фрагментов", languageLabels[languageIds.indexOf(language).coerceAtLeast(0)], languageLabels) {
                            language = languageIds[languageLabels.indexOf(it)]
                            prefs.edit().putString("foreign_language", language).apply(); ForeignTts.reset()
                        }
                        Text("Для иностранного текста предпочитается установленный офлайн-голос другого движка; его настройки сети действуют отдельно. Неоднозначная латиница в авторежиме считается английской. Если читалка сама указывает язык фрагмента (Readest), используется он. При сбое — приблизительная транслитерация без остановки книги.",
                            style = MaterialTheme.typography.bodySmall)
                        if (engines.isEmpty()) Text("Других движков TTS на телефоне не найдено.")
                        HorizontalDivider()
                        Text("Читалки", style = MaterialTheme.typography.titleLarge)
                        Text("Moon+ Reader и другие читалки, которые задают только язык, получают мультиязычную озвучку сами.")
                        Text("Readest и читалки с выбором голоса: выберите голос «…-multilingual-MyTTS» (например, ru-multilingual-MyTTS для русской книги). Он читает голосом, выбранным в MyTTS, с голосами книги и персонажей; другие языки — движком выше. В Readest он стоит первым в списке голосов MyTTS и выбирается по умолчанию. Голоса «…-supertonic-…» читают только выбранным голосом модели.")
                        if (!enabled || !ForeignTts.available(this@MultilingualActivity))
                            Text("Голос «Мультиязычный» появится, когда включены иностранные фрагменты и найден другой движок TTS.",
                                color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } },
                            modifier = Modifier.fillMaxWidth()) { Text("Настройки синтеза речи Android") }
                        Text("Readest использует движок по умолчанию: выберите там MyTTS.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable private fun Choice(label: String, selected: String, options: List<String>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(selected) }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; change(option) }) }
        }
    }
}
