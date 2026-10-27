package com.brahmadeo.supertonic.tts.books.smart

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.brahmadeo.supertonic.tts.llm.VoicePreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reads a retelling aloud through MyTTS itself, with the voice chosen in MyTTS and the system speech rate. It goes as a
 * trusted request of the app (like «Прослушать» of a voice): no LLM, no roles, no music — the cloud already put the
 * stress marks and ё into the retelling — and the reader's place and queue are not touched. Activity-owned. */
class SmartSpeech(context: Context) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(false)
    val speaking = mutable.asStateFlow()
    private var client: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var last: String? = null
    private var closed = false
    private var generation = 0

    fun speak(text: String) {
        if (closed) return
        stop()
        pending = text
        mutable.value = true
        if (client == null) client = TextToSpeech(app, { status -> handler.post {
            if (closed) return@post
            ready = status == TextToSpeech.SUCCESS
            if (ready) { client?.setOnUtteranceProgressListener(listener); play() } else { mutable.value = false; client?.shutdown(); client = null }
        } }, app.packageName) else if (ready) play()
    }

    private fun play() {
        val text = pending ?: return
        val tts = client ?: return
        pending = null
        // A paused reader blocks synthesis («Пауза» of the panel); the retelling is asked for while it is paused.
        com.brahmadeo.supertonic.tts.service.SleepTimer.manualResume()
        val chunks = SmartPrompts.speechChunks(text)
        val id = ++generation
        last = "smart-$id-${chunks.size - 1}"
        chunks.forEachIndexed { i, chunk ->
            if (tts.speak(chunk, TextToSpeech.QUEUE_ADD, VoicePreview.params(app), "smart-$id-$i") != TextToSpeech.SUCCESS) { stop(); return }
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) = Unit
        override fun onDone(id: String?) { handler.post { if (id == last) mutable.value = false } }
        @Deprecated("Deprecated in Java") override fun onError(id: String?) { handler.post { if (id?.startsWith("smart-$generation-") == true) mutable.value = false } }
        override fun onStop(id: String?, interrupted: Boolean) { handler.post { if (id?.startsWith("smart-$generation-") == true) mutable.value = false } }
    }

    fun stop() {
        pending = null; last = null; generation++
        if (mutable.value) client?.stop()
        mutable.value = false
    }

    fun close() { stop(); closed = true; client?.shutdown(); client = null; ready = false }
}
