package com.brahmadeo.supertonic.tts.ambience

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.brahmadeo.supertonic.tts.BooksActivity
import com.brahmadeo.supertonic.tts.books.BookLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** «Создать фоновые звуки» of a prepared book: in a foreground service (OnePlus kills a background app holding the
 * ~3 GB the generator needs), one book at a time, stoppable; finished clips are kept, so it continues where it stopped. */
object AmbienceGeneration {
    data class State(val running: Boolean = false, val book: Long? = null, val done: Int = 0, val total: Int = 0,
                     val place: String = "", val message: String = "")
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var stopped = false

    @Synchronized fun start(context: Context, book: Long): Boolean {
        if (job != null) return false
        val ctx = context.applicationContext
        mutable.value = State(true, book, message = "")
        ContextCompat.startForegroundService(ctx, Intent(ctx, AmbienceGenerationService::class.java))
        stopped = false
        job = scope.launch {
            try {
                val sha = requireNotNull(BookLibrary.contentSha(ctx, book)) { "Книга не найдена" }
                val scenes = BookLibrary.scenes(ctx, book)
                require(scenes.locations.isNotEmpty() || scenes.moods.moods.isNotEmpty()) { "В книге нет локаций: подготовьте её с «Локации и фоновые звуки»" }
                // «Музыка по настроению» needs its model (and the NPU graphs when the NPU is on).
                if (AmbienceSettings.moodMusic(ctx.getSharedPreferences("SupertonicPrefs", 0)) && !AmbienceModel.musicReady(ctx))
                    AmbienceModel.download(ctx) { text, _ -> mutable.value = State(true, book, place = text) }
                AmbienceBook.generate(ctx, sha, scenes, progress = { done, total, place -> mutable.value = State(true, book, done, total, place) },
                    cancelled = { stopped })
                mutable.value = State(book = book, message = "Фоновые звуки созданы: ${scenes.locations.size} локаций")
            } catch (_: InterruptedException) { mutable.value = State(book = book, message = "Создание звуков остановлено; готовые сохранены")
            } catch (e: Exception) { mutable.value = State(book = book, message = "Не удалось создать звуки: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                synchronized(this@AmbienceGeneration) { job = null }
                ctx.stopService(Intent(ctx, AmbienceGenerationService::class.java))
            }
        }
        return true
    }

    fun stop() { stopped = true }
}

class AmbienceGenerationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { AmbienceGeneration.stop(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("ambience_generation", "Фоновые звуки", NotificationManager.IMPORTANCE_LOW))
        startForeground(4208, notification(AmbienceGeneration.state.value))
        if (wake?.isHeld != true) wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyTTS:Ambience").apply { acquire(60 * 60 * 1000L) }
        scope.launch { AmbienceGeneration.state.collect { if (it.running) manager.notify(4208, notification(it)) } }
        return START_NOT_STICKY
    }
    private fun notification(state: AmbienceGeneration.State): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, BooksActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, AmbienceGenerationService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "ambience_generation").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MyTTS: фоновые звуки").setContentText(state.place.ifEmpty { "Подготовка генератора" })
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(state.total, state.done, state.total == 0)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stop).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { AmbienceGeneration.stop(); stopSelf() }
    override fun onDestroy() {
        scope.cancel()
        if (wake?.isHeld == true) wake?.release()
        wake = null
        super.onDestroy()
    }
}
