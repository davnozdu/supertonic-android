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
import com.brahmadeo.supertonic.tts.foreign.ForeignLanguages
import com.brahmadeo.supertonic.tts.foreign.ForeignTts
import com.brahmadeo.supertonic.tts.foreign.MultilingualVoice
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import java.util.Locale

/** «Мультиязычная озвучка»: the languages read by other Android TTS engines, each with its own engine. Shared by
 * every model and every reader (Moon+ by language, Readest through the one «MyTTS Мультиязычный» voice). */
class MultilingualActivity : ComponentActivity() {
    /** Latin, Greek, Arabic… scripts: Cyrillic text always goes to the Russian model, so uk/bg are not offered. */
    private val offered = listOf("en", "cs", "de", "fr", "es", "it", "pl", "pt", "nl", "sk", "sl", "hr", "sv", "da", "fi",
        "et", "lv", "lt", "hu", "ro", "tr", "id", "vi", "el", "ar", "hi", "ja", "ko")

    private fun title(language: String) = Locale.forLanguageTag(language).getDisplayLanguage(Locale("ru"))
        .replaceFirstChar { it.titlecase(Locale("ru")) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SupertonicTheme {
                val prefs = remember { getSharedPreferences("SupertonicPrefs", MODE_PRIVATE) }
                val engines = remember { ForeignTts.engines(this) }
                var enabled by remember { mutableStateOf(ForeignTts.enabled(this)) }
                var languages by remember { mutableStateOf(ForeignTts.languages(this)) }
                fun save(list: List<ForeignLanguages.Entry>) {
                    languages = list
                    prefs.edit().putString(ForeignLanguages.KEY, ForeignLanguages.serialize(list)).apply(); ForeignTts.reset()
                }
                val engineLabels = listOf("Системный движок Android") + engines.map { "${it.title} (${it.id})" }
                fun engineLabel(id: String) = engineLabels.getOrElse(engines.indexOfFirst { it.id == id } + 1) { engineLabels[0] }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Мультиязычная озвучка", style = MaterialTheme.typography.headlineMedium)
                        Text("Русский текст читает выбранная модель MyTTS, остальные языки — подключённые ниже движки Android TTS. Настройки общие для всех моделей и читалок.")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Читать другие языки другими движками", Modifier.weight(1f).padding(end = 12.dp))
                            Switch(enabled, { enabled = it; prefs.edit().putBoolean("foreign_tts", it).apply(); ForeignTts.reset() })
                        }
                        Text("Языки", style = MaterialTheme.typography.titleLarge)
                        if (languages.isEmpty()) Text("Ни одного языка не подключено.")
                        languages.forEachIndexed { index, entry ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    Choice(title(entry.language), engineLabel(entry.engine), engineLabels) { value ->
                                        val i = engineLabels.indexOf(value)
                                        save(languages.toMutableList().also { it[index] = entry.copy(engine = if (i <= 0) "" else engines[i - 1].id) })
                                    }
                                }
                                TextButton(onClick = { save(languages.filterIndexed { i, _ -> i != index }) }) { Text("Убрать") }
                            }
                        }
                        val free = offered.filter { code -> languages.none { it.language == code } }
                        Choice("Добавить язык", "Выбрать…", free.map { title(it) }) { value ->
                            val code = free[free.map { title(it) }.indexOf(value)]
                            save(languages + ForeignLanguages.Entry(code, languages.lastOrNull()?.engine ?: ""))
                        }
                        Text("Язык фрагмента: если читалка его указала (Readest) — он; иначе по тексту среди подключённых языков (чешские буквы, встроенный определитель языка Android); если не ясно — первый в списке. Для каждого языка берётся установленный офлайн-голос движка. При сбое — приблизительная транслитерация без остановки книги.",
                            style = MaterialTheme.typography.bodySmall)
                        if (engines.isEmpty()) Text("Других движков TTS на телефоне не найдено.", color = MaterialTheme.colorScheme.error)
                        HorizontalDivider()
                        Text("Голоса MyTTS для Readest и KOReader, ожидание LLM в таких читалках — в пункте меню «Сторонние читалки».", style = MaterialTheme.typography.bodySmall)
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
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; change(option) }) }
        }
    }
}
