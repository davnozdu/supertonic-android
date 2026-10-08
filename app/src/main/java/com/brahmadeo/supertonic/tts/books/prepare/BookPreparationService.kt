package com.brahmadeo.supertonic.tts.books.prepare

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.brahmadeo.supertonic.tts.BooksActivity
import kotlinx.coroutines.*

/** Dedicated foreground service: long preparation does not depend on an Activity or the screen.
 * Android can redeliver the document intent after reclaiming the process; completed cloud responses
 * are reused. A force-stop by the user is respected. */
class BookPreparationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private var observer: Job? = null
    private var limit: Job? = null
    private var cancellation: com.brahmadeo.supertonic.tts.llm.LlmProviders.CloudCancellation? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            if (BookPreparation.state.value.running) BookPreparation.stop() else stopSelf()
            return START_NOT_STICKY
        }
        val uri = intent?.data ?: run { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("book_preparation", "Подготовка книг", NotificationManager.IMPORTANCE_LOW))
        startForeground(4207, notification(BookPreparation.State(true, "Подготовка книги")))
        if (wake?.isHeld != true) wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyTTS:BookPreparation").apply { acquire(90 * 60 * 1000L) }
        if (observer == null) observer = scope.launch {
            BookPreparation.state.collect { state ->
                if (state.running) manager.notify(4207, notification(state))
            }
        }
        // In addition to each request's ten-minute deadline, bound the whole session.
        if (limit == null) limit = scope.launch { delay(90 * 60 * 1000L); BookPreparation.stop() }
        BookPreparation.execute(this, uri, intent.getBooleanExtra("thinking", true))
        cancellation = BookPreparation.cancellationScope()
        return START_REDELIVER_INTENT
    }

    private fun notification(state: BookPreparation.State): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, BooksActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, BookPreparationService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "book_preparation").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MyTTS: подготовка книги").setContentText(state.stage)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(state.total, state.done, state.total == 0)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stop).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { BookPreparation.stop(); stopSelf() }
    override fun onDestroy() {
        cancellation?.cancel()
        scope.cancel()
        if (wake?.isHeld == true) wake?.release()
        wake = null
        super.onDestroy()
    }
}
