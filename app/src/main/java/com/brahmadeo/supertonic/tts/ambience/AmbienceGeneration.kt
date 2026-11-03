package com.brahmadeo.supertonic.tts.ambience

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.brahmadeo.supertonic.tts.BooksActivity
import com.brahmadeo.supertonic.tts.books.BookLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * «Создать фоновые звуки», «Своя музыка» и подготовка NPU считаются в отдельном процессе (":soundgen") и там же
 * умирают вместе с сервисом. Причина — память: средняя модель вместе с декодером на NPU берёт ~4,8 ГБ dmabuf, и пока
 * это жило в процессе чтения, OnePlus убивал именно читалку. В основном процессе здесь остаётся только состояние для
 * экранов (StateFlow), прогресс приходит широковещательными сообщениями из рабочего процесса.
 */
object AmbienceGeneration {
    data class State(val running: Boolean = false, val book: Long? = null, val done: Int = 0, val total: Int = 0,
                     val place: String = "", val message: String = "")

    const val ACTION_BOOK = "com.brahmadeo.supertonic.tts.ambience.BOOK"
    const val ACTION_MUSIC = "com.brahmadeo.supertonic.tts.ambience.MUSIC"
    const val ACTION_COMPILE = "com.brahmadeo.supertonic.tts.ambience.COMPILE"
    const val ACTION_STOP = "com.brahmadeo.supertonic.tts.ambience.STOP"
    private const val ACTION_STATE = "com.brahmadeo.supertonic.tts.ambience.STATE"

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    @Volatile private var app: Context? = null
    @Volatile private var listening = false

    /** Called once from the application (main process): brings the worker's progress into [state]. */
    fun attach(context: Context) {
        if (listening) return
        listening = true
        val ctx = context.applicationContext
        app = ctx
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                val running = intent.getBooleanExtra("running", false)
                val book = intent.getLongExtra("book", -1L)
                mutable.value = State(running, book.takeIf { it >= 0 }, intent.getIntExtra("done", 0), intent.getIntExtra("total", 0),
                    intent.getStringExtra("place").orEmpty(), intent.getStringExtra("message").orEmpty())
                // The night book queue waited for this NPU compilation: it goes on now.
                if (!running && intent.getBooleanExtra("compiled", false)) com.brahmadeo.supertonic.tts.books.prepare.BookQueue.resume(ctx)
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun launch(context: Context, intent: Intent): Boolean {
        val ctx = context.applicationContext
        app = ctx
        ContextCompat.startForegroundService(ctx, Intent(ctx, AmbienceGenerationService::class.java).also { it.putExtras(intent) })
        return true
    }

    @Synchronized fun start(context: Context, book: Long): Boolean {
        if (mutable.value.running) return false
        mutable.value = State(true, book, place = "Подготовка")
        return launch(context, Intent(ACTION_BOOK).putExtra("book", book))
    }

    /** «Своя музыка»: one track from a description, generated in the worker process (its own model, own memory). */
    @Synchronized fun startMusic(context: Context, description: String, seconds: Int): Boolean {
        if (mutable.value.running) return false
        mutable.value = State(true, null, place = "Подготовка")
        return launch(context, Intent(ACTION_MUSIC).putExtra("description", description).putExtra("seconds", seconds))
    }

    @Synchronized fun startNpuCompile(context: Context): Boolean {
        if (mutable.value.running) return false
        mutable.value = State(true, null, place = "Подготовка NPU")
        return launch(context, Intent(ACTION_COMPILE))
    }

    fun stop() {
        val ctx = app ?: return
        // A cancel goes to the worker as a foreground service (the app is in the foreground when the user taps Stop);
        // if even that is refused, the service is stopped outright — finished clips are already on disk.
        val intent = Intent(ctx, AmbienceGenerationService::class.java).setAction(ACTION_STOP)
        runCatching { ContextCompat.startForegroundService(ctx, intent) }
            .onFailure { runCatching { ctx.stopService(intent) } }
    }

    /** The service reports here from its own process: nothing is shared but these extras. */
    internal fun publish(context: Context, state: State, compiled: Boolean = false) {
        runCatching {
            context.sendBroadcast(Intent(ACTION_STATE).setPackage(context.packageName)
                .putExtra("running", state.running).putExtra("book", state.book ?: -1L)
                .putExtra("done", state.done).putExtra("total", state.total)
                .putExtra("place", state.place).putExtra("message", state.message).putExtra("compiled", compiled))
        }
    }
}

class AmbienceGenerationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wake: PowerManager.WakeLock? = null
    private var job: Job? = null
    @Volatile private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (intent.action == AmbienceGeneration.ACTION_STOP) {
            // Still satisfies the foreground-service contract of the start that brought us here.
            startForeground(4208, notification(AmbienceGeneration.State(true, null, place = "Остановка")))
            stopped = true
            AmbienceGeneration.publish(this, AmbienceGeneration.State(message = "Остановлено; готовое сохранено"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("ambience_generation", "Фоновые звуки", NotificationManager.IMPORTANCE_LOW))
        startForeground(4208, notification(AmbienceGeneration.State(true, null, place = "Подготовка")))
        holdWake()
        if (job?.isAlive == true) {
            // This process already owns the generator: a second start is a duplicate (the main process may have
            // restarted and lost the running state).
            return START_NOT_STICKY
        }
        stopped = false
        if (!GeneratorSlot.tryAcquire()) {
            report(AmbienceGeneration.State(message = "Генератор уже занят другим запуском — дождитесь его окончания"))
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        job = scope.launch {
            try {
                com.brahmadeo.supertonic.tts.utils.HtpGate.attach(this@AmbienceGenerationService)
                when (intent.action) {
                    AmbienceGeneration.ACTION_BOOK -> book(intent.getLongExtra("book", -1L))
                    AmbienceGeneration.ACTION_MUSIC -> music(intent.getStringExtra("description").orEmpty(), intent.getIntExtra("seconds", 30))
                    else -> compile()
                }
            } catch (_: InterruptedException) {
                report(AmbienceGeneration.State(message = "Остановлено; готовое сохранено"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(AmbienceGeneration.State(message = "Не удалось: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                GeneratorSlot.release()
                if (wake?.isHeld == true) wake?.release()
                wake = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun report(state: AmbienceGeneration.State, compiled: Boolean = false) {
        AmbienceGeneration.publish(this, state, compiled)
        if (state.running) (getSystemService(NotificationManager::class.java)).notify(4208, notification(state))
    }

    private suspend fun book(book: Long) {
        val sha = requireNotNull(BookLibrary.contentSha(this, book)) { "Книга не найдена" }
        val scenes = BookLibrary.scenes(this, book)
        require(scenes.locations.isNotEmpty() || scenes.moods.moods.isNotEmpty()) { "В книге нет локаций: подготовьте её с «Локации и фоновые звуки»" }
        // «Музыка по настроению» needs its model (and the NPU graphs when the NPU is on).
        if (AmbienceSettings.moodMusic(getSharedPreferences("SupertonicPrefs", 0)) && !AmbienceModel.musicReady(this))
            AmbienceModel.download(this) { text, _ -> report(AmbienceGeneration.State(true, book, place = text)) }
        AmbienceBook.generate(this, sha, scenes, progress = { done, total, place -> report(AmbienceGeneration.State(true, book, done, total, place)) },
            cancelled = { stopped })
        report(AmbienceGeneration.State(book = book, message = "Фоновые звуки созданы: ${scenes.locations.size} локаций" +
            (AmbienceModel.takeLowMemory()?.let { ". $it" } ?: "")))
        NpuNightJob.schedule(this)
    }

    private suspend fun music(description: String, seconds: Int) {
        if (!AmbienceModel.musicReady(this)) AmbienceModel.downloadMusic(this) { text, _ -> report(AmbienceGeneration.State(true, null, place = text)) }
        val track = com.brahmadeo.supertonic.tts.music.GeneratedMusic.create(this, description, seconds,
            { report(AmbienceGeneration.State(true, null, place = it)) }, { stopped })
        report(AmbienceGeneration.State(message = "Музыка «${track.title}» готова — выберите её в списке треков"))
    }

    private suspend fun compile() {
        val done = AmbienceModel.compileMissing(this, { stopped || !NpuNightJob.charging(this) }) {
            report(AmbienceGeneration.State(true, null, place = it))
        }
        report(AmbienceGeneration.State(message = if (done) "NPU подготовлен: звуки и музыка создаются на NPU"
            else "Подготовка NPU продолжится при следующей зарядке"), compiled = true)
        NpuNightJob.schedule(this)
    }

    private fun holdWake() {
        // Без подсчёта ссылок: повторный onStartCommand не должен оставлять снимок висеть до часа после работы.
        if (wake == null) wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyTTS:Ambience")
            .apply { setReferenceCounted(false) }
        wake!!.acquire(60 * 60 * 1000L)
    }

    private fun notification(state: AmbienceGeneration.State): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, BooksActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, AmbienceGenerationService::class.java).setAction(AmbienceGeneration.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "ambience_generation").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MyTTS: фоновые звуки").setContentText(state.place.ifEmpty { "Подготовка генератора" })
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(state.total, state.done, state.total == 0)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stop).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) { stopped = true; stopSelf() }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        if (wake?.isHeld == true) wake?.release()
        wake = null
        super.onDestroy()
        // The service is the only component of ":soundgen": ending it must take the process (and the NPU buffers the
        // kernel still caches for it) with it. Finished clips are on disk, so the next start continues.
        com.brahmadeo.supertonic.tts.utils.HtpGate.detach("job finished")
    }
}
