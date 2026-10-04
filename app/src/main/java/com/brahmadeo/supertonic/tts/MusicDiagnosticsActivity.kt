package com.brahmadeo.supertonic.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.brahmadeo.supertonic.tts.music.BackgroundMusic
import com.brahmadeo.supertonic.tts.music.MusicCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Root/ADB-only silent check; never sends book text to a cloud provider. */
class MusicDiagnosticsActivity : ComponentActivity() {
    companion object { private val running=java.util.concurrent.atomic.AtomicBoolean() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(!running.compareAndSet(false,true)) { finish();return }
        lifecycleScope.launch {
            val prefs=getSharedPreferences("SupertonicPrefs",0)
            val llm=getSharedPreferences("llm_settings",0)
            val keys=listOf("background_music_enabled","background_music_track","background_music_volume")
            val saved=keys.associateWith { prefs.all[it] }
            val oldMode=llm.getString("mode",null)
            var tts: TextToSpeech?=null
            try {
                BackgroundMusic.initialize(this@MusicDiagnosticsActivity)
                MusicCatalog.download(this@MusicDiagnosticsActivity) { percent,_ -> if(percent%25==0) Log.i("MusicCheck","Download $percent%") }
                val track=MusicCatalog.tracks(this@MusicDiagnosticsActivity).first()
                check(MusicCatalog.ready(this@MusicDiagnosticsActivity,track)!=null)
                llm.edit().putString("mode","OFF").commit()
                prefs.edit().putString("background_music_track","ready:${track.id}")
                    .putBoolean("background_music_enabled",true).putInt("background_music_volume",0).commit()
                val initialized=CompletableDeferred<Int>()
                val done=ConcurrentHashMap<String,CompletableDeferred<Unit>>()
                tts=TextToSpeech(applicationContext,{ initialized.complete(it) },packageName)
                check(withTimeout(15000) { initialized.await() }==TextToSpeech.SUCCESS)
                tts.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
                    override fun onStart(id: String?) { Log.i("MusicCheck","TTS start id=$id") }
                    override fun onDone(id: String?) { id?.let { done[it]?.complete(Unit) } }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { id?.let { done[it]?.completeExceptionally(IllegalStateException("TTS failed")) } }
                })
                tts.setLanguage(java.util.Locale.forLanguageTag("ru"))
                val volume=Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,0f) }
                val sample="За окном шёл дождь. В тёплой комнате горел свет, а на столе лежала открытая книга. Всё было спокойно."
                check(tts.speak(sample,TextToSpeech.QUEUE_FLUSH,volume,"music-pause")==TextToSpeech.SUCCESS)
                withTimeout(90000) { while(!BackgroundMusic.snapshot().playing) delay(50) }
                check(BackgroundMusic.snapshot().looping)
                Log.i("MusicCheck","PASS real TTS callback starts looping music")
                check(tts.stop()==TextToSpeech.SUCCESS)
                withTimeout(5000) { while(BackgroundMusic.snapshot().playing) delay(50) }
                delay(150)
                val paused=BackgroundMusic.snapshot().position
                delay(350)
                check(kotlin.math.abs(BackgroundMusic.snapshot().position-paused)<100)
                Log.i("MusicCheck","PASS stop pauses music; track position is retained")
                val finished=CompletableDeferred<Unit>();done["music-resume"]=finished
                check(tts.speak(sample,TextToSpeech.QUEUE_ADD,volume,"music-resume")==TextToSpeech.SUCCESS)
                withTimeout(90000) { while(!BackgroundMusic.snapshot().playing) delay(50) }
                check(BackgroundMusic.snapshot().position>=paused)
                withTimeout(90000) { finished.await() }
                withTimeout(5000) { while(BackgroundMusic.snapshot().playing) delay(50) }
                Log.i("MusicCheck","PASS resume and natural end")
                val rendered=CompletableDeferred<Unit>();done["music-file"]=rendered
                val file=File(cacheDir,"music-check.wav")
                check(tts.synthesizeToFile("Проверка файла.",volume,file,"music-file")==TextToSpeech.SUCCESS)
                withTimeout(90000) { rendered.await() }
                check(!BackgroundMusic.snapshot().reading && !BackgroundMusic.snapshot().playing)
                file.delete()
                Log.i("MusicCheck","PASS file synthesis does not start music; all checks passed")
            } catch(t: Exception) { Log.e("MusicCheck","FAIL music integration",t) }
            finally {
                tts?.stop();tts?.shutdown()
                withContext(Dispatchers.IO) {
                    val editor=prefs.edit()
                    for((key,value) in saved) when(value) {
                        null -> editor.remove(key)
                        is Boolean -> editor.putBoolean(key,value)
                        is Int -> editor.putInt(key,value)
                        is String -> editor.putString(key,value)
                    }
                    editor.commit()
                    if(oldMode==null) llm.edit().remove("mode").commit() else llm.edit().putString("mode",oldMode).commit()
                }
                running.set(false);finish()
            }
        }
    }
}
