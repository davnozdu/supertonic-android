package com.brahmadeo.supertonic.tts.llm

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.brahmadeo.supertonic.tts.utils.AssetManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Activity-owned TTS client. Preview never changes the book's voice or LLM settings. */
class VoicePreview(context: Context) {
    companion object {
        const val SAMPLE = "Ти́хий ве́чер. За окно́м шелестя́т дере́вья. Вы гото́вы? Тогда́ начнём."
        fun params(context: Context) = Bundle().apply { putBoolean(context.packageName + ".voice_preview", true) }
        fun requested(context: Context, params: Bundle?, callerUid: Int): Boolean =
            callerUid == context.applicationInfo.uid && params?.getBoolean(context.packageName + ".voice_preview", false) == true
        fun voice(name: String) = Voice("ru-supertonic-$name", Locale("ru"), Voice.QUALITY_VERY_HIGH,
            Voice.LATENCY_NORMAL, false, emptySet())
    }
    data class State(val activeVoice: String? = null, val preparing: Boolean = false, val message: String = "")
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var client: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var nextId = 0L
    private var currentId: String? = null
    private var pending: Pair<String, Boolean>? = null
    private val timeout = Runnable {
        stop()
        mutableState.value = State(message = "Проба не завершилась. Попробуйте ещё раз.")
    }

    fun toggle(name: String) {
        if (closed) return
        if (mutableState.value.activeVoice == name) { stop(); return }
        val replacingOwn = currentId != null
        stop()
        val mixed = com.brahmadeo.supertonic.tts.books.BookVoiceRef.parse(name)
        if (if (mixed != null) com.brahmadeo.supertonic.tts.books.BookVoiceCatalog.file(app, name) == null
            else name !in AssetManager.russianVoices(app) || !AssetManager.voiceFile(app, name).isFile) {
            mutableState.value = State(message = "Голос не установлен. Сначала скачайте его.")
            return
        }
        pending = name to replacingOwn
        mutableState.value = State(name, true, "Подготовка голоса ${com.brahmadeo.supertonic.tts.books.BookVoiceCatalog.label(name)}…")
        handler.postDelayed(timeout, 30_000)
        if (client == null) {
            client = TextToSpeech(app, { status -> handler.post {
                if (!closed) {
                    ready = status == TextToSpeech.SUCCESS
                    if (ready) {
                        client?.setOnUtteranceProgressListener(listener)
                        playPending()
                    } else {
                        stop()
                        client?.shutdown(); client = null
                        mutableState.value = State(message = "Не удалось подключить движок. Попробуйте ещё раз.")
                    }
                }
            } }, app.packageName)
        } else if (ready) playPending()
    }
    private fun playPending() {
        val (name, replacingOwn) = pending ?: return
        val tts = client ?: return
        pending = null
        // A preview should not queue behind a running book or compete with its audio.
        if (!replacingOwn && tts.isSpeaking) {
            stop()
            mutableState.value = State(message = "Остановите чтение книги перед прослушиванием голоса.")
            return
        }
        val mixed = com.brahmadeo.supertonic.tts.books.BookVoiceRef.parse(name)
        if (mixed == null && tts.setVoice(voice(name)) != TextToSpeech.SUCCESS) {
            stop()
            mutableState.value = State(message = "Этот голос недоступен. Проверьте его установку.")
            return
        }
        tts.setSpeechRate(1f)
        val id = "voice-preview-${++nextId}"
        currentId = id
        val parameters = params(app).apply { if (mixed != null) putString(app.packageName + ".book_voice", name) }
        if (tts.speak(SAMPLE, TextToSpeech.QUEUE_FLUSH, parameters, id) != TextToSpeech.SUCCESS) {
            stop()
            mutableState.value = State(message = "Не удалось запустить пробу голоса.")
        }
    }
    private fun complete(id: String?, error: Boolean) { handler.post {
        if (!closed && id == currentId) {
            handler.removeCallbacks(timeout)
            currentId = null
            mutableState.value = State(message = if (error) "Не удалось озвучить пробу. Проверьте модель и голос." else "")
        }
    } }
    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) { handler.post {
            if (!closed && id == currentId) {
                mutableState.value = mutableState.value.copy(preparing = false, message = "")
                Log.i("VoicePreview", "Playing voice=${mutableState.value.activeVoice}")
            }
        } }
        override fun onDone(id: String?) = complete(id, false)
        override fun onError(id: String?) = complete(id, true)
        override fun onStop(id: String?, interrupted: Boolean) = complete(id, false)
    }
    fun stop() {
        handler.removeCallbacks(timeout)
        pending = null
        val hadUtterance = currentId != null
        currentId = null // Ignore late callbacks belonging to the previous sample.
        if (hadUtterance) client?.stop()
        mutableState.value = State()
    }
    fun close() {
        stop(); closed = true
        client?.shutdown(); client = null; ready = false
    }
}
