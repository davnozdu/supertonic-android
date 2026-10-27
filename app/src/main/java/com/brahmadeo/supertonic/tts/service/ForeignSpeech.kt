package com.brahmadeo.supertonic.tts.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Speech of another app over a book (MacroDroid reading an SMS aloud through MyTTS while KOReader, Moon+ or Readest
 * reads): the two voices together could not be understood. The reader is the app that keeps asking for speech; a
 * request of any other app while it reads is a message. For the message MyTTS holds the audio focus (transient), so
 * the reader pauses its book like for a call and goes on after it; the message is read at once by the dictionaries and
 * leaves the reading's state (its ahead audio, music, notification, LLM queue) alone. */
object ForeignSpeech {
    /** The reader: at least this many requests, spread over at least [READER_SPAN_MS] (a burst of messages is not one). */
    private const val READER_REQUESTS = 4
    private const val READER_SPAN_MS = 45_000L
    private const val WINDOW_MS = 120_000L
    /** A reader quiet for longer than this is not reading any more: a message then needs no pause. */
    private const val READING_GAP_MS = 30_000L
    /** Released at the latest after this, whatever happens to the message. */
    private const val MAX_HOLD_MS = 60_000L

    private val requests = HashMap<Int, ArrayDeque<Long>>()
    private val main = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private val release = Runnable { abandon() }

    /** Every speech request, of any app. */
    @Synchronized fun note(uid: Int) {
        val now = SystemClock.elapsedRealtime()
        val q = requests.getOrPut(uid) { ArrayDeque() }
        q.addLast(now)
        while (q.isNotEmpty() && now - q.first() > WINDOW_MS) q.removeFirst()
        requests.entries.removeAll { (_, v) -> v.isEmpty() || now - v.last() > WINDOW_MS }
    }

    /** The app reading a book now, or null. */
    @Synchronized private fun reader(now: Long): Int? = requests.entries.filter { (_, q) ->
        q.size >= READER_REQUESTS && q.last() - q.first() >= READER_SPAN_MS && now - q.last() <= READING_GAP_MS
    }.maxByOrNull { it.value.last() }?.key

    /** True when [uid]'s request is a message over another app's reading (MyTTS itself never is). */
    fun foreign(context: Context, uid: Int): Boolean {
        if (uid == context.applicationInfo.uid) return false
        val reader = reader(SystemClock.elapsedRealtime()) ?: return false
        return reader != uid
    }

    /** A short spoken answer of the reader itself (KOReader's plugin says «до конца главы…» through speak() while its
     * book, read by files, is paused): read like a message, without the focus — it is the reader's own voice. */
    private val asides = LinkedHashSet<String>()
    @Synchronized fun aside(text: String) { asides += text; while (asides.size > 8) asides.remove(asides.first()) }
    @Synchronized fun isAside(text: String) = text in asides

    /** The message starts (queued or synthesized): the reader is asked to pause. */
    fun begin(context: Context) = main.post {
        main.removeCallbacks(release)
        main.postDelayed(release, MAX_HOLD_MS)
        if (focus != null) return@post
        val am = context.getSystemService(AudioManager::class.java) ?: return@post
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }
            .build()
        if (runCatching { am.requestAudioFocus(request) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focus = request
            android.util.Log.i("ForeignSpeech", "Message of another app over a reading: audio focus taken")
        }
        appContext = context.applicationContext
    }

    /** Its synthesis is done; [audioMs] of it still plays from [playingFrom] (elapsedRealtime of its first audio). */
    fun end(audioMs: Long, playingFrom: Long) = main.post {
        val at = maxOf(playingFrom, 0L) + audioMs + 400
        val delay = (at - SystemClock.elapsedRealtime()).coerceIn(200L, MAX_HOLD_MS)
        main.removeCallbacks(release)
        main.postDelayed(release, delay)
    }

    private var appContext: Context? = null

    private fun abandon() {
        val request = focus ?: return
        focus = null
        appContext?.getSystemService(AudioManager::class.java)?.let { runCatching { it.abandonAudioFocusRequest(request) } }
        android.util.Log.i("ForeignSpeech", "Message over: audio focus given back")
    }
}
