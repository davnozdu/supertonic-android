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
 * stress marks and ё into the retelling — and the reader's place and queue are not touched. Owned by the app, not a
 * screen: [SmartRecapService] keeps it going with the panel closed and the screen off. */
object SmartSpeech {
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(false)
    val speaking = mutable.asStateFlow()
    private var app: Context? = null
    private var client: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var last: String? = null
    private var generation = 0

    /** Main thread only. */
    fun speak(context: Context, text: String) {
        stop()
        val ctx = context.applicationContext.also { app = it }
        pending = text
        mutable.value = true
        if (client == null) client = TextToSpeech(ctx, { status -> handler.post {
            ready = status == TextToSpeech.SUCCESS
            if (ready) { client?.setOnUtteranceProgressListener(listener); play() } else { mutable.value = false; client?.shutdown(); client = null }
        } }, ctx.packageName) else if (ready) play()
    }

    private fun play() {
        val text = pending ?: return
        val tts = client ?: return
        val ctx = app ?: return
        pending = null
        // A paused reader blocks synthesis («Пауза» of the panel); the retelling is asked for while it is paused.
        com.brahmadeo.supertonic.tts.service.SleepTimer.manualResume()
        val chunks = SmartPrompts.speechChunks(text)
        val id = ++generation
        last = "smart-$id-${chunks.size - 1}"
        chunks.forEachIndexed { i, chunk ->
            if (tts.speak(chunk, TextToSpeech.QUEUE_ADD, VoicePreview.params(ctx), "smart-$id-$i") != TextToSpeech.SUCCESS) { stop(); return }
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) = Unit
        override fun onDone(id: String?) { handler.post { if (id == last) mutable.value = false } }
        @Deprecated("Deprecated in Java") override fun onError(id: String?) { handler.post { if (id?.startsWith("smart-$generation-") == true) mutable.value = false } }
        override fun onStop(id: String?, interrupted: Boolean) { handler.post { if (id?.startsWith("smart-$generation-") == true) mutable.value = false } }
    }

    /** Main thread only. */
    fun stop() {
        pending = null; last = null; generation++
        if (mutable.value) client?.stop()
        mutable.value = false
    }

    /** When nothing is read any more: the engine connection is not kept. */
    fun release() { stop(); client?.shutdown(); client = null; ready = false }
}
