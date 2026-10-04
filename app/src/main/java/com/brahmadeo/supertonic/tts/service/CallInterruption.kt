package com.brahmadeo.supertonic.tts.service

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent

/** Observe cellular/VoIP audio mode without phone-number or call-log permissions. */
internal object CallInterruption {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashMap<Any, () -> Unit>()
    private var audio: AudioManager? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null
    @Volatile private var blocked = false
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            if (listeners.isNotEmpty()) main.postDelayed(this, 500)
        }
    }
    fun register(context: Context, owner: Any, stop: () -> Unit) {
        main.post {
            audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            listeners[owner] = stop
            if (listeners.size == 1) {
                if (Build.VERSION.SDK_INT >= 31) {
                    val listener = AudioManager.OnModeChangedListener { update(it) }
                    runCatching { audio!!.addOnModeChangedListener(context.mainExecutor, listener) }
                        .onSuccess { modeListener = listener }
                }
                main.post(poll) // Also covers vendors missing a mode-change notification.
            }
            refresh()
            if (blocked) stop()
        }
    }
    fun unregister(owner: Any) { main.post {
        listeners.remove(owner)
        if (listeners.isEmpty()) {
            main.removeCallbacks(poll)
            if (Build.VERSION.SDK_INT >= 31) modeListener?.let { listener ->
                runCatching { audio?.removeOnModeChangedListener(listener) }
            }
            modeListener = null
        }
    } }
    fun active(): Boolean = blocked || runCatching { interruptingMode(audio?.mode ?: AudioManager.MODE_NORMAL) }.getOrDefault(false)
    private fun refresh() { audio?.let { runCatching { update(it.mode) } } }
    internal fun interruptingMode(mode: Int): Boolean = mode == AudioManager.MODE_RINGTONE ||
        mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == 4 // call screening
    private fun update(mode: Int) {
        val next = interruptingMode(mode)
        if (next == blocked) return
        blocked = next
        if (!next) return // Never restart a book automatically after a call.
        Log.i("CallInterruption", "Call audio mode=$mode: stopping speech and music")
        com.brahmadeo.supertonic.tts.music.BackgroundMusic.stopTts()
        listeners.values.toList().forEach { stop -> runCatching { stop() } }
        // Tell the reader to keep its position instead of requesting the next paragraph.
        runCatching {
            audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audio?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        }
    }
}
