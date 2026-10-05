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
                var selected by remember { mutableStateOf(prefs.getString("background_music_track","").orEmpty()) }
                var customName by remember { mutableStateOf(if(MusicFiles.custom(this)!=null) prefs.getString("background_music_name","Свой MP3").orEmpty() else "") }
                var volume by remember { mutableIntStateOf(prefs.getInt("background_music_volume",BackgroundMusic.DEFAULT_VOLUME).coerceIn(0,100)) }
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
                    prefs.edit().putString("background_music_track",track).putBoolean("background_music_enabled",true).apply()
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        Text("Фоновая музыка",style=MaterialTheme.typography.headlineMedium)
                        Text("Выбранный трек играет по кругу во время чтения. Пауза останавливает речь и музыку; продолжение сохраняет место в треке.")
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Включить музыку",Modifier.weight(1f))
                            Switch(checked=enabled && selected.isNotEmpty(),enabled=!busy && selected.isNotEmpty(),onCheckedChange={
                                enabled=it; prefs.edit().putBoolean("background_music_enabled",it).apply()
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
                                        if(selected=="ready:${track.id}") { selected=""; enabled=false }
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
