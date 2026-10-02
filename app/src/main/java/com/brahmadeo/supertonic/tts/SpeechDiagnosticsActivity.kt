package com.brahmadeo.supertonic.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.brahmadeo.supertonic.tts.utils.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Non-exported, root/ADB-only integration check. Synthetic text, no playback. */
class SpeechDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
                val oldModel = AssetManager.getModelType(this@SpeechDiagnosticsActivity)
                val oldVoice = prefs.getString("selected_voice", "F3.json")
                val oldLang = prefs.getString("selected_lang", "en")
                val model = intent.getStringExtra("model") ?: AssetManager.SILERO_MODEL
                var tts: TextToSpeech? = null
                try {
                    require(model in setOf(AssetManager.SILERO_MODEL, AssetManager.TERA_MODEL))
                    AssetManager.setModelType(this@SpeechDiagnosticsActivity, model)
                    prefs.edit().putString("selected_lang", "ru")
                        .putString("selected_voice", if (model == AssetManager.SILERO_MODEL) "kseniya.json" else "ru_f1.json").apply()
                    SupertonicTTS.release()
                    if (!AssetManager.isReady(this@SpeechDiagnosticsActivity)) {
                        AssetManager.download(this@SpeechDiagnosticsActivity) { _, progress ->
                            Log.i("SpeechCheck", "Download progress=${(progress*100).toInt()}")
                        }
                    }
                    val ready = CompletableFuture<Int>()
                    withContext(Dispatchers.Main) {
                        tts = TextToSpeech(this@SpeechDiagnosticsActivity, { ready.complete(it) }, packageName)
                    }
                    check(ready.get(10, TimeUnit.SECONDS) == TextToSpeech.SUCCESS)
                    check(tts!!.setLanguage(Locale("ru")) >= TextToSpeech.LANG_AVAILABLE)
                    tts!!.setSpeechRate(1.1f)
                    val cases = listOf(
                        "По-прежнему светло. Когда ветер стих мы открыли окно. В списке 1001 имя и 1101 запись. Ты готов? Да я готов!",
                        "По-прежнему светло. Когда ветер стих мы открыли окно. В списке 1001 имя и 1101 запись. Ты готов? Да я готов!",
                        "Он сказал: Hello world! Потом добавил: Dobrý den, jak se máte? Всё хорошо.",
                        "Hello world! There are 1001 names.",
                        "Dobrý den, jak se máte? Máme 1101 záznamů."
                    )
                    for ((index, text) in cases.withIndex()) {
                        if (index == 1) delay(6000)
                        val language = when (index) { 3 -> "en"; 4 -> "cs"; else -> "ru" }
                        check(tts!!.setLanguage(Locale(language)) >= TextToSpeech.LANG_AVAILABLE)
                        val id = "speech-check-$index"; val done = CompletableFuture<Unit>()
                        tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(Unit) }
                            override fun onError(utteranceId: String?) {
                                if (utteranceId == id) done.completeExceptionally(IllegalStateException("TTS case $index failed"))
                            }
                        })
                        val output = File(cacheDir, "speech-check-$index.wav")
                        try {
                            val started = android.os.SystemClock.elapsedRealtime()
                            check(tts!!.synthesizeToFile(text, Bundle(), output, id) == TextToSpeech.SUCCESS)
                            done.get(90, TimeUnit.SECONDS)
                            val header = ByteArray(44)
                            output.inputStream().use { check(it.read(header) == 44) }
                            check(String(header, 0, 4, Charsets.US_ASCII) == "RIFF")
                            val wave = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                            val rate = wave.getInt(24); val channels = wave.getShort(22).toInt()
                            check(rate == if (model == AssetManager.SILERO_MODEL) 48000 else 44100)
                            check(channels == 1 && output.length() > 44)
                            Log.i("SpeechCheck", "PASS model=$model case=$index rate=$rate channels=$channels bytes=${output.length()} ms=${android.os.SystemClock.elapsedRealtime()-started}")
                        } finally { output.delete() }
                    }
                    Log.i("SpeechCheck", "ALL CASES PASSED model=$model")
                } catch (t: Throwable) { Log.e("SpeechCheck", "Integration check failed", t) }
                finally {
                    tts?.stop(); tts?.shutdown()
                    AssetManager.setModelType(this@SpeechDiagnosticsActivity, oldModel)
                    prefs.edit().putString("selected_voice", oldVoice).putString("selected_lang", oldLang).apply()
                    SupertonicTTS.release()
                    Log.i("SpeechCheck", "Original model and voice restored")
                }
            }
            finish()
        }
    }
}
