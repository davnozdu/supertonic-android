package com.brahmadeo.supertonic.tts.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.utils.AssetManager
import com.brahmadeo.supertonic.tts.utils.ModelContext
import com.brahmadeo.supertonic.tts.utils.ModelDownloadForeground
import com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache
import com.brahmadeo.supertonic.tts.pocket.PocketVoices
import com.brahmadeo.supertonic.tts.pocket.PocketExtraVoices
import com.brahmadeo.supertonic.tts.tera.TeraVoiceLibrary
import kotlinx.coroutines.*

/** A pack belongs to its model. The voice selector lists installed voices only. */
@Composable fun VoicePackDownload(model:String,revision:Int=0,onChanged:()->Unit) {
    val app=LocalContext.current
    val context=remember(app,model) { ModelContext(app,model) }
    if(model !in com.brahmadeo.supertonic.tts.books.BookVoiceRef.models) return
    fun ready()=when(model) {
        AssetManager.POCKET_MODEL -> PocketVoices.ready(context) && PocketExtraVoices.assets(context).all { PocketExtraVoices.ready(context,it.name) }
        AssetManager.TERA_MODEL -> TeraVoiceLibrary.missing(context).isEmpty()
        else -> AssetManager.isReady(context)
    }
    var complete by remember(model,revision) { mutableStateOf(ready()) }
    var busy by remember(model) { mutableStateOf(false) }
    var status by remember(model) { mutableStateOf("") }
    val scope=rememberCoroutineScope()
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        OutlinedButton(enabled=!busy && !complete,modifier=Modifier.fillMaxWidth(),onClick={
            busy=true
            scope.launch {
                try {
                    ModelDownloadForeground.run(context) {
                        fun progress(s:String,p:Float) { status="$s · ${(p*100).toInt()} %" }
                        when(model) {
                            AssetManager.POCKET_MODEL -> {
                                PocketVoices.download(context) { s,p -> progress(s,p*.5f) }
                                val voices=PocketExtraVoices.assets(context)
                                voices.forEachIndexed { i,v -> PocketExtraVoices.ensure(context,v.name) { s,p -> progress(s,.5f+.5f*(i+p)/voices.size) } }
                            }
                            AssetManager.TERA_MODEL -> TeraVoiceLibrary.download(context,::progress)
                            else -> AssetManager.download(context,::progress)
                        }
                    }
                    complete=ready();status="Пак голосов скачан";SpeechPreparationCache.clear();onChanged()
                } catch(t:CancellationException) { throw t }
                  catch(t:Exception) { status="Не удалось скачать: ${t.message}" }
                  finally { busy=false }
            }
        }) { Text("Скачать пак голосов") }
        Text(if(status.isNotBlank()) status else if(complete) "Пак голосов этой модели уже установлен" else when(model) {
            AssetManager.POCKET_MODEL -> "Shtorm: 7 основных и 12 дополнительных голосов. Дополнительный MP3-пак — 1,16 МБ."
            AssetManager.TERA_MODEL -> "Дополнительные стили TeraTTS; голоса появятся в списке как «Tera+»."
            else -> "Голоса входят в пакет выбранной модели и скачиваются с её общими весами."
        },style=MaterialTheme.typography.bodySmall)
    }
}
