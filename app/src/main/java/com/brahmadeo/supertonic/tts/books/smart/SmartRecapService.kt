package com.brahmadeo.supertonic.tts.books.smart

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.brahmadeo.supertonic.tts.QuickReadActivity
import com.brahmadeo.supertonic.tts.service.ReadingControls
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** «Умная книга» in the background: the retelling is made and read aloud with the panel closed and the screen off
 * (OnePlus freezes a background app and drops its network otherwise). Its notification shows the stage and has
 * «Стоп» and «Продолжить чтение». It ends by itself when nothing is made or read. */
class SmartRecapService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    /** The result being waited for is read aloud when it comes. */
    private var speakNext = false
    /** A streamed retelling is being read: [spoken] characters of it are handed to the speech already. */
    private var streaming = false
    private var spoken = 0

    /** Reads the retelling sentence by sentence while it is written; the rest (or the whole) when it is done. */
    private fun follow(s: SmartRecap.State) {
        if (s.running) {
            // A JSON answer (a model ignoring «plain text») is read only when it is done and unwrapped.
            if (s.partial.isBlank() || s.partial.trimStart().startsWith("{")) return
            if (!streaming) { SmartSpeech.begin(this); streaming = true; spoken = 0 }
            val end = SmartPrompts.sentenceEnd(s.partial, spoken)
            if (end > spoken) { SmartSpeech.append(s.partial.substring(spoken, end)); spoken = end }
            return
        }
        speakNext = false
        if (streaming) {
            streaming = false
            if (s.result == null) { SmartSpeech.stop(); return }
            s.partial.drop(spoken).takeIf { it.isNotBlank() }?.let { SmartSpeech.append(it) }
            SmartSpeech.finish()
        } else s.result?.let { SmartSpeech.speak(this, it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Умная книга", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        ServiceCompat.startForeground(this, ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        when (intent?.action) {
            STOP -> { SmartRecap.cancel(); SmartSpeech.stop(); stopSelf(); return START_NOT_STICKY }
            RESUME -> { SmartSpeech.stop(); SmartRecap.clear(); ReadingControls.play(this); stopSelf(); return START_NOT_STICKY }
            LISTEN -> SmartRecap.state.value.result?.let { SmartSpeech.speak(this, it) }
            WAIT -> {
                speakNext = intent.getBooleanExtra("speak", true)
                streaming = false; spoken = 0
                // An answer made before for this place is already there (the state may not change, so no new emission).
                val s = SmartRecap.state.value
                if (speakNext && !s.running && s.result != null) { speakNext = false; SmartSpeech.speak(this, s.result) }
                if (!s.running && !SmartSpeech.speaking.value && !speakNext && watcher != null) { stopSelf(); return START_NOT_STICKY }
            }
        }
        if (watcher == null) watcher = scope.launch {
            combine(SmartRecap.state, SmartSpeech.speaking) { s, speaking -> s to speaking }.collect { (s, _) ->
                if (speakNext) follow(s)
                // Done: no retelling is made or read. A text-only result stays in the panel, not in this service.
                if (!s.running && !SmartSpeech.speaking.value && !speakNext) stopSelf()
                else getSystemService(NotificationManager::class.java).notify(ID, notification())
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): android.app.Notification {
        val s = SmartRecap.state.value
        val speaking = SmartSpeech.speaking.value
        fun action(action: String, code: Int) = PendingIntent.getForegroundService(this, code, Intent(this, SmartRecapService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 5421, Intent(this, QuickReadActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when {
            s.running -> s.stage + if (s.total > 0) " · ${s.done}/${s.total}" else ""
            speaking -> "Звучит пересказ"
            s.error != null -> s.error
            else -> "Готово"
        }
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle((s.kind?.title ?: "Умная книга") + if (s.book.isNotBlank()) " · «${s.book}»" else "")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply { if (s.running) setProgress(s.total, s.done, s.total == 0) }
            .addAction(android.R.drawable.ic_media_pause, "Стоп", action(STOP, 5422))
            .addAction(android.R.drawable.ic_media_play, "Продолжить чтение", PendingIntent.getActivity(this, 5423,
                Intent(this, ReaderReturnActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) { SmartRecap.cancel(); SmartSpeech.stop(); stopSelf() }

    override fun onDestroy() {
        scope.cancel()
        if (!SmartSpeech.speaking.value) SmartSpeech.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "smart_book"
        private const val ID = 4211
        private const val STOP = "stop"
        private const val RESUME = "resume"
        private const val LISTEN = "listen"
        private const val WAIT = "wait"

        /** After [SmartRecap.start]: keeps the work alive and reads the result when [speak]. */
        fun follow(context: Context, speak: Boolean) = start(context, Intent(context, SmartRecapService::class.java).setAction(WAIT).putExtra("speak", speak))
        /** Reads the current result again. */
        fun listen(context: Context) = start(context, Intent(context, SmartRecapService::class.java).setAction(LISTEN))
        fun stop(context: Context) = start(context, Intent(context, SmartRecapService::class.java).setAction(STOP))
        fun resume(context: Context) = start(context, Intent(context, SmartRecapService::class.java).setAction(RESUME))
        private fun start(context: Context, intent: Intent) { runCatching { ContextCompat.startForegroundService(context, intent) } }
    }
}
