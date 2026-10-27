package com.brahmadeo.supertonic.tts.books.smart

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.brahmadeo.supertonic.tts.llm.VoicePreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reads a retelling aloud through MyTTS itself, with the voice and speed chosen in MyTTS. It goes as a trusted request
 * of the app (like «Прослушать» of a voice): no LLM, no roles, no music; the cloud's stress marks are kept and the
 * dictionaries mark the rest. The reader's place and queue are not touched. Owned by the app, not a screen:
 * [SmartRecapService] keeps it going with the panel closed and the screen off.
 * A streamed retelling is read as it comes: [begin], then [append] for each finished piece, then [finish]. */
object SmartSpeech {
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(false)
    val speaking = mutable.asStateFlow()
    private var app: Context? = null
    private var client: TextToSpeech? = null
    private var ready = false
    /** Pieces waiting for the engine connection. */
    private val pending = mutableListOf<String>()
    private var generation = 0
    private var queued = 0
    private var done = 0
    private var finished = false

    /** The whole text at once. Main thread only. */
    fun speak(context: Context, text: String) { begin(context); append(text); finish() }

    /** Main thread only. */
    fun begin(context: Context) {
        stop()
        val ctx = context.applicationContext.also { app = it }
        mutable.value = true
        if (client == null) client = TextToSpeech(ctx, { status -> handler.post {
            ready = status == TextToSpeech.SUCCESS
            if (ready) { client?.setOnUtteranceProgressListener(listener); flush() } else { mutable.value = false; client?.shutdown(); client = null }
        } }, ctx.packageName)
        // A paused reader blocks synthesis («Пауза» of the panel); the retelling is asked for while it is paused.
        com.brahmadeo.supertonic.tts.service.SleepTimer.manualResume()
    }

    /** Main thread only. */
    fun append(text: String) {
        if (!mutable.value) return
        // Stress and ё are Silero's (the engine stresses a «smart_recap» request with Silero Stress alone): any mark the
        // model still put goes, so a wrong one («Ро́гожин») cannot stick.
        pending += SmartPrompts.speechChunks(text.replace("́", ""))
        flush()
    }

    /** Nothing more will come. Main thread only. */
    fun finish() {
        if (!mutable.value) return
        finished = true
        flush()
        if (queued == done && pending.isEmpty()) mutable.value = false
    }

    private fun flush() {
        val tts = client ?: return
        val ctx = app ?: return
        if (!ready) return
        // The speed chosen in MyTTS, not the system TTS rate (a reader's, often 2–2.5×: the retelling raced).
        tts.setSpeechRate(ctx.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getFloat("speed", 1f).coerceIn(0.5f, 2.5f))
        while (pending.isNotEmpty()) {
            val chunk = pending.removeAt(0)
            val params = VoicePreview.params(ctx).apply { putBoolean(ctx.packageName + ".smart_recap", true) }
            if (tts.speak(chunk, TextToSpeech.QUEUE_ADD, params, "smart-$generation-${queued++}") != TextToSpeech.SUCCESS) { stop(); return }
        }
    }

    private fun mine(id: String?) = id?.startsWith("smart-$generation-") == true

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) = Unit
        override fun onDone(id: String?) { handler.post {
            if (mine(id)) { done++; if (finished && done == queued && pending.isEmpty()) mutable.value = false }
        } }
        @Deprecated("Deprecated in Java") override fun onError(id: String?) { handler.post { if (mine(id)) stop() } }
        override fun onStop(id: String?, interrupted: Boolean) { handler.post { if (mine(id)) mutable.value = false } }
    }

    /** Main thread only. */
    fun stop() {
        pending.clear(); generation++; queued = 0; done = 0; finished = false
        if (mutable.value) client?.stop()
        mutable.value = false
    }

    /** When nothing is read any more: the engine connection is not kept. */
    fun release() { stop(); client?.shutdown(); client = null; ready = false }
}
