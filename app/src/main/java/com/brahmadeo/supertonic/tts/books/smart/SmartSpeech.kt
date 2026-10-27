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
 * of the app (like «Прослушать» of a voice): no LLM, no roles, no music; the engine stresses it with Silero alone. The
 * reader's place and queue are not touched. Owned by the app, not a screen: [SmartRecapService] keeps it going with the
 * panel closed and the screen off. A streamed retelling is read as it comes: [begin], [append] each finished piece,
 * [finish]. [pause] / [resume] continue from the piece that was playing (the KOReader window has them). */
object SmartSpeech {
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(false)
    /** True from [begin] until the end or [stop], also while paused. */
    val speaking = mutable.asStateFlow()
    @Volatile var paused = false; private set
    private var app: Context? = null
    private var client: TextToSpeech? = null
    private var ready = false
    /** Every piece of this retelling; [queued] of them are handed to the engine, [done] finished. */
    private val chunks = mutableListOf<String>()
    private var queued = 0
    private var done = 0
    private var generation = 0
    private var finished = false
    /** The engine connection goes a minute after the speech ended (a replay from KOReader has no service to end it). */
    private val idleRelease = Runnable { if (!mutable.value) release() }
    /** A pause left for long ends the retelling: no service and notification kept for nothing. */
    private val pauseLimit = Runnable { if (paused) stop() }

    /** The whole text at once. Main thread only. */
    fun speak(context: Context, text: String) { begin(context); append(text); finish() }

    /** Main thread only. */
    fun begin(context: Context) {
        stop()
        handler.removeCallbacks(idleRelease)
        val ctx = context.applicationContext.also { app = it }
        mutable.value = true
        if (client == null) client = TextToSpeech(ctx, { status -> handler.post {
            ready = status == TextToSpeech.SUCCESS
            if (ready) { client?.setOnUtteranceProgressListener(listener); flush() } else { stop(); client?.shutdown(); client = null }
        } }, ctx.packageName)
        // A paused reader blocks synthesis («Пауза» of the panel); the retelling is asked for while it is paused.
        com.brahmadeo.supertonic.tts.service.SleepTimer.manualResume()
    }

    /** Main thread only. */
    fun append(text: String) {
        if (!mutable.value) return
        // Any mark the model still put goes: Silero stresses the retelling, a wrong mark («Ро́гожин») cannot stick.
        chunks += SmartPrompts.speechChunks(text.replace("́", ""))
        flush()
    }

    /** Nothing more will come. Main thread only. */
    fun finish() {
        if (!mutable.value) return
        finished = true
        flush()
        ended()
    }

    /** Main thread only. The engine stops now; [resume] goes on from the piece that was playing. */
    fun pause() {
        if (!mutable.value || paused) return
        paused = true
        handler.postDelayed(pauseLimit, 15 * 60_000L)
        generation++ // the stopped utterances' callbacks are not this speech any more
        queued = done
        client?.stop()
    }

    /** Main thread only. */
    fun resume() {
        if (!mutable.value || !paused) return
        paused = false
        handler.removeCallbacks(pauseLimit)
        com.brahmadeo.supertonic.tts.service.SleepTimer.manualResume()
        flush()
        ended()
    }

    private fun ended() { if (finished && !paused && done >= chunks.size) { mutable.value = false; idleLater() } }
    private fun idleLater() { handler.removeCallbacks(idleRelease); handler.postDelayed(idleRelease, 60_000L) }

    private fun flush() {
        val tts = client ?: return
        val ctx = app ?: return
        if (!ready || paused) return
        // The speed chosen in MyTTS, not the system TTS rate (a reader's, often 2–2.5×: the retelling raced).
        tts.setSpeechRate(ctx.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getFloat("speed", 1f).coerceIn(0.5f, 2.5f))
        while (queued < chunks.size) {
            val params = VoicePreview.params(ctx).apply { putBoolean(ctx.packageName + ".smart_recap", true) }
            if (tts.speak(chunks[queued], TextToSpeech.QUEUE_ADD, params, "smart-$generation-$queued") != TextToSpeech.SUCCESS) { stop(); return }
            queued++
        }
    }

    private fun mine(id: String?) = id?.startsWith("smart-$generation-") == true

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) = Unit
        override fun onDone(id: String?) { handler.post { if (mine(id)) { done++; ended() } } }
        @Deprecated("Deprecated in Java") override fun onError(id: String?) { handler.post { if (mine(id)) stop() } }
        override fun onStop(id: String?, interrupted: Boolean) { handler.post { if (mine(id) && !paused) stop() } }
    }

    /** Main thread only. */
    fun stop() {
        val was = mutable.value
        chunks.clear(); generation++; queued = 0; done = 0; finished = false; paused = false
        if (was) client?.stop()
        mutable.value = false
        handler.removeCallbacks(pauseLimit)
        idleLater()
    }

    /** When nothing is read any more: the engine connection is not kept. */
    fun release() {
        chunks.clear(); generation++; queued = 0; done = 0; finished = false; paused = false; mutable.value = false
        handler.removeCallbacks(idleRelease); handler.removeCallbacks(pauseLimit)
        client?.shutdown(); client = null; ready = false
    }
}
