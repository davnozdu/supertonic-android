package com.brahmadeo.supertonic.tts.asr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.SmartBookActivity
import com.brahmadeo.supertonic.tts.books.smart.SmartMatcher
import com.brahmadeo.supertonic.tts.books.smart.SmartRecap
import com.brahmadeo.supertonic.tts.books.smart.SmartRecapService
import com.brahmadeo.supertonic.tts.books.smart.SmartSpeech
import com.brahmadeo.supertonic.tts.service.ReadingControls
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** «Спросить о книге» by voice: a small window over the reader listens to the question (it stops by itself after a
 * pause in speech), GigaAM recognises it on the phone and the question goes to [SmartRecap.ask] like a typed one; the
 * answer comes where the retellings come (MyTTS's panel or KOReader's window). Extras from KOReader: «text» — its page,
 * «sentence» — the one read last. Nothing is recorded outside this window, the audio is kept only in memory. */
class VoiceAskActivity : ComponentActivity() {
    private enum class Stage { NO_MODEL, PERMISSION, LISTENING, RECOGNISING, DONE, ERROR }

    private var stage by mutableStateOf(Stage.LISTENING)
    private var level by mutableFloatStateOf(0f)
    private var heard by mutableStateOf("")
    private var message by mutableStateOf("")
    @Volatile private var stopRequested = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else { stage = Stage.ERROR; message = "Без доступа к микрофону голосовой вопрос невозможен." }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        setContent { SupertonicTheme { Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(24.dp).widthIn(min = 280.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Спросить о книге", style = MaterialTheme.typography.titleLarge)
                when (stage) {
                    Stage.NO_MODEL -> {
                        Text("Для голосового вопроса нужна модель распознавания речи GigaAM-v3 (≈${GigaAm.SIZE_MB} МБ). " +
                            "Скачайте её: MyTTS → «Умная книга» → «Голосовой ввод».")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { finish() }) { Text("Закрыть") }
                            Button(onClick = { startActivity(Intent(this@VoiceAskActivity, SmartBookActivity::class.java)); finish() }) { Text("Открыть настройки") }
                        }
                    }
                    Stage.PERMISSION -> Text("Разрешите доступ к микрофону.")
                    Stage.LISTENING -> {
                        Text("Говорите. Запись закончится сама после паузы.")
                        LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { stopRequested = true; heard = ""; finish() }) { Text("Отмена") }
                            Button(onClick = { stopRequested = true }) { Text("Готово") }
                        }
                    }
                    Stage.RECOGNISING -> { Text("Распознаю…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    Stage.DONE -> Text("«$heard»")
                    Stage.ERROR -> {
                        Text(message)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { finish() }) { Text("Закрыть") }
                            Button(onClick = { listen() }) { Text("Ещё раз") }
                        }
                    }
                }
            }
        } } }
        when {
            !SmartRecap.questions(this) -> { stage = Stage.ERROR; message = "Вопросы о книге выключены: MyTTS → «Умная книга» → «Вопросы о книге»." }
            !GigaAm.ready(this) -> stage = Stage.NO_MODEL
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                stage = Stage.PERMISSION; permission.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> listen()
        }
    }

    override fun onStop() { super.onStop(); if (stage == Stage.LISTENING) stopRequested = true }

    /** Records until a pause after speech (1.2 s quieter than the noise floor ×3), [MAX_MS] at most; nothing heard within
     * [WAIT_MS] — an error. Then recognises and asks. */
    private fun listen() {
        stage = Stage.LISTENING; stopRequested = false; level = 0f
        ReadingControls.pause(this)
        SmartSpeech.stop()
        lifecycleScope.launch {
            val samples = withContext(Dispatchers.IO) { record() }
            if (isFinishing) return@launch
            if (samples == null) { stage = Stage.ERROR; message = "Не удалось включить микрофон."; return@launch }
            if (samples.isEmpty()) { stage = Stage.ERROR; message = "Ничего не услышал. Говорите ближе к телефону."; return@launch }
            stage = Stage.RECOGNISING
            val text = withContext(Dispatchers.Default) { runCatching { GigaAm.recognize(this@VoiceAskActivity, samples) }.getOrNull() }
            if (text.isNullOrBlank()) { stage = Stage.ERROR; message = "Не удалось разобрать вопрос. Попробуйте ещё раз."; return@launch }
            heard = text; stage = Stage.DONE
            ask(text)
            kotlinx.coroutines.delay(1200)
            finish()
        }
    }

    private fun ask(question: String) {
        val page = intent.getStringExtra("text").orEmpty()
        val place = if (page.isNotBlank()) SmartMatcher.locate(this, page) else SmartMatcher.last(this)
        val last = intent.getStringExtra("sentence").orEmpty().ifBlank { SmartMatcher.lastHeard }
        if (SmartRecap.ask(this, question, place, page.ifBlank { last }, last)) SmartRecapService.follow(this, SmartRecap.answerSpoken(this))
    }

    private fun record(): FloatArray? {
        val rate = GigaAmFeatures.SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            @Suppress("MissingPermission")
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        } catch (_: Exception) { return null }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) { recorder.release(); return null }
        val out = ShortArray(rate * MAX_MS / 1000)
        var n = 0
        val chunk = ShortArray(rate / 20) // 50 ms
        var floor = Float.MAX_VALUE
        var spoke = false
        var quietMs = 0
        try {
            recorder.startRecording()
            while (!stopRequested && n < out.size) {
                val got = recorder.read(chunk, 0, chunk.size)
                if (got <= 0) break
                var sum = 0.0
                for (i in 0 until got) { val v = chunk[i] / 32768.0; sum += v * v; if (n < out.size) out[n++] = chunk[i] }
                val rms = sqrt(sum / got).toFloat()
                val elapsed = n * 1000 / rate
                if (elapsed <= 300) { floor = minOf(floor, rms); continue }
                val loud = rms > maxOf(floor * 3f, 0.01f)
                level = (rms / maxOf(floor * 12f, 0.08f)).coerceIn(0f, 1f)
                if (loud) { spoke = true; quietMs = 0 } else quietMs += 50
                if (spoke && quietMs >= 1200) break
                if (!spoke && elapsed >= WAIT_MS) return FloatArray(0)
            }
        } finally { runCatching { recorder.stop() }; recorder.release() }
        if (!spoke) return FloatArray(0)
        return FloatArray(n) { out[it] / 32768f }
    }

    companion object {
        private const val MAX_MS = 20_000
        private const val WAIT_MS = 7_000
    }
}
