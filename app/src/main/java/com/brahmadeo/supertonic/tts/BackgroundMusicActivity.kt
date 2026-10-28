package com.brahmadeo.supertonic.tts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.ambience.AmbienceModel
import com.brahmadeo.supertonic.tts.ambience.AmbienceSettings
import com.brahmadeo.supertonic.tts.ambience.AmbienceSettings.musicOn
import com.brahmadeo.supertonic.tts.music.BackgroundMusic
import com.brahmadeo.supertonic.tts.music.MusicFiles
import com.brahmadeo.supertonic.tts.music.MusicCatalog
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class BackgroundMusicActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BackgroundMusic.initialize(this)
        setContent {
            SupertonicTheme {
                val prefs=remember { getSharedPreferences("SupertonicPrefs",MODE_PRIVATE) }
                val catalog=remember { MusicCatalog.tracks(this) }
                var installed by remember { mutableStateOf(catalog.filter { MusicCatalog.ready(this,it)!=null }.map { it.id }.toSet()) }
                var enabled by remember { mutableStateOf(prefs.getBoolean("background_music_enabled",false)) }
                var selected by remember { mutableStateOf(MusicCatalog.ensureDefault(this)) }
                var customName by remember { mutableStateOf(if(MusicFiles.custom(this)!=null) prefs.getString("background_music_name","Свой MP3").orEmpty() else "") }
                var volume by remember { mutableIntStateOf(prefs.getInt("background_music_volume",BackgroundMusic.DEFAULT_VOLUME).coerceIn(0,100)) }
                var ambience by remember { mutableStateOf(AmbienceSettings.enabled(prefs)) }
                var ambienceVolume by remember { mutableIntStateOf(AmbienceSettings.volume(prefs)) }
                var ambienceReady by remember { mutableStateOf(AmbienceModel.ready(this)) }
                var ambienceNpu by remember { mutableStateOf(prefs.getBoolean(AmbienceModel.NPU_KEY,false)) }
                var ambienceTame by remember { mutableStateOf(AmbienceSettings.tame(prefs)) }
                var breathe by remember { mutableStateOf(AmbienceSettings.breathe(prefs)) }
                var events by remember { mutableStateOf(AmbienceSettings.events(prefs)) }
                var chapters by remember { mutableStateOf(AmbienceSettings.chapters(prefs)) }
                var ambienceVariants by remember { mutableIntStateOf(AmbienceSettings.variants(prefs)) }
                var busy by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("") }
                val error by BackgroundMusic.status.collectAsState()
                val scope=rememberCoroutineScope()
                val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if(uri!=null) {
                        busy=true; message="Загрузка MP3…"
                        scope.launch {
                            try {
                                customName=MusicFiles.import(this@BackgroundMusicActivity,uri)
                                selected="custom"; enabled=true; message="MP3 добавлен"
                            } catch(t: Exception) { message="Не удалось загрузить MP3: ${t.message.orEmpty()}" }
                            finally { busy=false }
                        }
                    }
                }
                fun select(track: String) {
                    selected=track; enabled=true
                    prefs.edit().putString("background_music_track",track).musicOn().apply()
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        Text("Фоновая музыка",style=MaterialTheme.typography.headlineMedium)
                        Text("Выбранный трек играет по кругу во время чтения. Пауза останавливает речь и музыку; продолжение сохраняет место в треке.")
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Включить музыку",Modifier.weight(1f))
                            Switch(checked=enabled && selected.isNotEmpty(),enabled=!busy && selected.isNotEmpty(),onCheckedChange={
                                enabled=it
                                if(it) prefs.edit().musicOn().apply() else prefs.edit().putBoolean("background_music_enabled",false).apply()
                            })
                        }
                        Text("Громкость фона: $volume %",style=MaterialTheme.typography.titleMedium)
                        Slider(value=volume.toFloat(),onValueChange={ volume=it.roundToInt(); BackgroundMusic.volumePreview(volume) },
                            onValueChangeFinished={ prefs.edit().putInt("background_music_volume",volume).apply() },
                            valueRange=0f..100f,steps=99)
                        Text("0 % — беззвучно. По умолчанию 10 %. Громкость речи не изменяется.",style=MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("Готовые треки",style=MaterialTheme.typography.titleLarge)
                        Button(enabled=!busy && installed.size<catalog.size,onClick={
                            busy=true
                            scope.launch {
                                try {
                                    MusicCatalog.download(this@BackgroundMusicActivity) { percent,title ->
                                        runOnUiThread { message="$title — $percent %" }
                                    }
                                    message="Музыка загружена. Выберите трек ниже."
                                } catch(t: Exception) { message="Не удалось скачать: ${t.message.orEmpty()}" }
                                finally {
                                    installed=catalog.filter { MusicCatalog.ready(this@BackgroundMusicActivity,it)!=null }.map { it.id }.toSet()
                                    selected=MusicCatalog.ensureDefault(this@BackgroundMusicActivity)
                                    busy=false
                                }
                            }
                        }) { Text(when { installed.size==catalog.size -> "Музыка скачана"; installed.isEmpty() -> "Скачать музыку"; else -> "Скачать новые композиции" }) }
                        Text("${catalog.size} ${compositions(catalog.size)}, около ${(catalog.sumOf { it.size } + 524288) / 1048576} МБ. После скачивания интернет не нужен.",style=MaterialTheme.typography.bodySmall)
                        for(track in catalog) {
                            val available=track.id in installed
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text(track.title+if(available) "" else " (не скачано)",Modifier.weight(1f).padding(top=12.dp))
                                RadioButton(selected=selected=="ready:${track.id}",enabled=!busy && available,onClick={select("ready:${track.id}")})
                            }
                            if(available) TextButton(enabled=!busy,onClick={
                                busy=true
                                scope.launch {
                                    try {
                                        MusicCatalog.remove(this@BackgroundMusicActivity,track)
                                        installed=installed-track.id
                                        selected=prefs.getString("background_music_track","").orEmpty()
                                        enabled=prefs.getBoolean("background_music_enabled",false)
                                        message="Композиция удалена с телефона"
                                    } catch(t: Exception) { message="Не удалось удалить: ${t.message.orEmpty()}" }
                                    finally { busy=false }
                                }
                            }) { Text("Удалить композицию") }
                        }
                        HorizontalDivider()
                        Text("Свой трек",style=MaterialTheme.typography.titleLarge)
                        if(customName.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text(customName,Modifier.weight(1f).padding(top=12.dp))
                                RadioButton(selected=selected=="custom",enabled=!busy,onClick={select("custom")})
                            }
                        }
                        Button(enabled=!busy,onClick={picker.launch(arrayOf("audio/mpeg","audio/mp3"))}) {
                            Text(if(customName.isEmpty()) "Загрузить свой MP3" else "Заменить свой MP3")
                        }
                        if(customName.isNotEmpty()) OutlinedButton(enabled=!busy,onClick={
                            busy=true
                            scope.launch {
                                try {
                                    MusicFiles.remove(this@BackgroundMusicActivity)
                                    customName=""
                                    if(selected=="custom") { selected=""; enabled=false }
                                    message="Свой MP3 удалён"
                                } catch(t: Exception) { message="Не удалось удалить: ${t.message.orEmpty()}" }
                                finally { busy=false }
                            }
                        }) { Text("Удалить свой MP3") }
                        Text("Файл сохраняется в приложении и доступен без интернета.",style=MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("Фоновые звуки локаций · эксперимент",style=MaterialTheme.typography.titleLarge)
                        Text("Для книг, подготовленных в MyTTS с локациями: звук места действия по ходу чтения — поезд, кафе, улица, лес. " +
                            "Звуки создаются на телефоне один раз для каждой локации книги и хранятся в её файле. В сценах с локацией играет её звук, " +
                            "а там, где сцены нет (рассуждения автора, нераспознанные места), — фоновая музыка, если она включена. " +
                            "Одновременно они не звучат, переход плавный.",style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Включить фоновые звуки",Modifier.weight(1f))
                            Switch(checked=ambience,enabled=!busy,onCheckedChange={
                                ambience=it; AmbienceSettings.setAmbience(prefs,it)
                            })
                        }
                        Text("Громкость звуков: $ambienceVolume %",style=MaterialTheme.typography.titleMedium)
                        Slider(value=ambienceVolume.toFloat(),onValueChange={ ambienceVolume=it.roundToInt() },
                            onValueChangeFinished={ prefs.edit().putInt(AmbienceSettings.VOLUME,ambienceVolume).apply() },
                            valueRange=0f..100f,steps=99)
                        Text("По умолчанию 30 %. Громкость речи не изменяется.",style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Сглаживать резкие звуки",Modifier.weight(1f))
                            Switch(checked=ambienceTame,enabled=!busy,onCheckedChange={ ambienceTame=it; prefs.edit().putBoolean(AmbienceSettings.TAME,it).apply() })
                        }
                        Text("Гудки, смех, удары и другие события сцены не громче ровного фона больше чем на ~3 дБ и не перебивают голос. " +
                            "Действует сразу, звуки пересоздавать не нужно.",style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Звуки событий",Modifier.weight(1f))
                            Switch(checked=events,enabled=!busy,onCheckedChange={ events=it; prefs.edit().putBoolean(AmbienceSettings.EVENTS,it).apply() })
                        }
                        Text("Короткие звуки того, что происходит в тексте: хлопнула дверь, выстрел, заржала лошадь, ударил колокол — " +
                            "в момент, когда голос до этого доходит. Нужна книга, подготовленная с локациями в этой версии или новее.",
                            style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Дыхание звука",Modifier.weight(1f))
                            Switch(checked=breathe,enabled=!busy,onCheckedChange={ breathe=it; prefs.edit().putBoolean(AmbienceSettings.BREATHE,it).apply() })
                        }
                        Text("Как в кино: новая сцена первые секунды звучит чуть громче, а под речью фон (звуки и музыка) на 3 дБ тише " +
                            "и поднимается в паузах между абзацами.",style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Переходы между главами",Modifier.weight(1f))
                            Switch(checked=chapters,enabled=!busy,onCheckedChange={ chapters=it; prefs.edit().putBoolean(AmbienceSettings.CHAPTERS,it).apply() })
                        }
                        Text("На новой главе подготовленной книги фон плавно затихает и возвращается — слышно, что глава сменилась.",
                            style=MaterialTheme.typography.bodySmall)
                        Text("Вариантов звука на сцену: $ambienceVariants",style=MaterialTheme.typography.titleMedium)
                        Slider(value=ambienceVariants.toFloat(),onValueChange={ ambienceVariants=it.roundToInt() },
                            onValueChangeFinished={ prefs.edit().putInt(AmbienceSettings.VARIANTS,ambienceVariants).apply() },
                            valueRange=2f..8f,steps=5)
                        Text("По 30 с каждый, играют в случайном порядке: ровный фон и варианты с событиями места (гудок паровоза, лай собаки). " +
                            "Больше вариантов — разнообразнее, но дольше создание и больше файл книги (≈240 КБ на вариант, ≈3 с на NPU). " +
                            "Недостающие варианты создаются кнопкой в карточке книги. По умолчанию 3.",style=MaterialTheme.typography.bodySmall)
                        if(com.brahmadeo.supertonic.tts.utils.Npu.supported(this@BackgroundMusicActivity)) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text("Генерация на NPU · эксперимент",Modifier.weight(1f))
                                Switch(checked=ambienceNpu,enabled=!busy,onCheckedChange={
                                    ambienceNpu=it; prefs.edit().putBoolean(AmbienceModel.NPU_KEY,it).apply()
                                })
                            }
                            Text("Нагружает процессор примерно в 3 раза меньше. Дополнительно ${AmbienceModel.npuBytes()/1048576} МБ; " +
                                "первая подготовка NPU занимает около 2 минут.",style=MaterialTheme.typography.bodySmall)
                        }
                        val npuMissing=ambienceNpu && ambienceReady && !AmbienceModel.npuReady(this@BackgroundMusicActivity)
                        Button(enabled=!busy && (!ambienceReady || npuMissing),onClick={
                            busy=true
                            scope.launch {
                                try {
                                    AmbienceModel.download(this@BackgroundMusicActivity) { text,_ -> runOnUiThread { message=text } }
                                    message="Генератор звуков установлен"
                                } catch(t: Exception) { message="Не удалось скачать: ${t.message.orEmpty()}" }
                                finally { ambienceReady=AmbienceModel.ready(this@BackgroundMusicActivity); busy=false }
                            }
                        }) { Text(when { npuMissing -> "Скачать модель для NPU"; ambienceReady -> "Генератор звуков скачан"
                            else -> "Скачать генератор звуков (${AmbienceModel.totalBytes()/1048576} МБ)" }) }
                        if(ambienceReady) OutlinedButton(enabled=!busy,onClick={
                            busy=true
                            scope.launch {
                                try {
                                    AmbienceModel.remove(this@BackgroundMusicActivity)
                                    message="Генератор звуков удалён"
                                } catch(t: Exception) { message="Не удалось удалить: ${t.message.orEmpty()}" }
                                finally { ambienceReady=AmbienceModel.ready(this@BackgroundMusicActivity); busy=false }
                            }
                        }) { Text("Удалить генератор звуков") }
                        Text("Генератор нужен только чтобы создать звуки при подготовке книги; книги со звуками играют и без него. " +
                            "Модель Stable Audio 3 Small SFX. Powered by Stability AI. Лицензия Stability AI Community License; " +
                            "текстовый энкодер — Gemma Terms of Use.",style=MaterialTheme.typography.bodySmall)
                        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if(message.isNotBlank()) Text(message)
                        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun compositions(n: Int) = when {
    n % 100 in 11..14 -> "композиций"
    n % 10 == 1 -> "композиция"
    n % 10 in 2..4 -> "композиции"
    else -> "композиций"
}
