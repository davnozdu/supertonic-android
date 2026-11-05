package com.brahmadeo.supertonic.tts.books.prepare

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** «Подготовить ночью»: books waiting for preparation, each a copy of its file (the picker's permission does not
 * outlive the app) with the options chosen when it was added. Prepared one after another between 00:00 and 06:00
 * while the phone charges and nobody uses it ([BookNightJob]); a book once started is finished even after 06:00. */
object BookQueue {
    data class Entry(val id: String, val name: String, val file: File, val replies: Boolean, val smart: Boolean, val scenes: Boolean,
                     val thinking: Boolean, val failures: Int, val text: Boolean = false,
                     /** На паузе: облако отказало (лимит, токены, сеть); причина и когда продолжить сама. */
                     val pausedReason: String? = null, val resumeAt: Long = 0L, val pauses: Int = 0) {
        val paused: Boolean get() = pausedReason != null
    }
    const val NIGHT_KEY = "book_queue_night"
    const val NIGHT_FROM = 0
    const val NIGHT_TO = 6
    private const val MAX_FAILURES = 2
    private val mutable = MutableStateFlow<List<Entry>>(emptyList())
    val entries = mutable.asStateFlow()

    private fun dir(context: Context) = File(context.filesDir, "book-queue")
    private fun prefs(context: Context) = context.getSharedPreferences("book_preparation", 0)
    private fun readEntry(directory: File) = android.util.AtomicFile(File(directory, "entry.json")).openRead()
        .bufferedReader().use { JSONObject(it.readText()) }
    private fun writeEntry(directory: File, json: JSONObject) {
        val file = android.util.AtomicFile(File(directory, "entry.json"))
        val output = file.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (t: Throwable) { file.failWrite(output); throw t }
    }
    private fun queued(context: Context, uri: Uri): File? {
        if (uri.scheme != "file") return null
        val source = uri.path?.let { File(it) } ?: return null
        val directory = BookQueuePath.directory(dir(context), source) ?: return null
        return runCatching {
            val name = readEntry(directory).getString("name")
            directory.takeIf { source.canonicalFile == File(directory, name).canonicalFile }
        }.getOrNull()
    }
    fun nightOn(context: Context) = prefs(context).getBoolean(NIGHT_KEY, false)
    fun setNight(context: Context, on: Boolean) { prefs(context).edit().putBoolean(NIGHT_KEY, on).apply(); BookNightJob.schedule(context) }

    fun refresh(context: Context): List<Entry> {
        val list = dir(context).listFiles()?.filter { it.isDirectory }?.mapNotNull { d -> runCatching {
            val o = readEntry(d)
            val file = File(d, o.getString("name")).takeIf { it.isFile } ?: return@runCatching null
            Entry(d.name, o.getString("name"), file, o.optBoolean("replies", true), o.optBoolean("smart"), o.optBoolean("scenes"),
                o.optBoolean("thinking", true), o.optInt("failures"), o.optBoolean("text"),
                o.optString("paused").takeIf { it.isNotEmpty() }, o.optLong("resume_at"), o.optInt("pauses"))
        }.getOrNull() }?.sortedBy { it.id }.orEmpty()
        mutable.value = list
        return list
    }

    /** Copies [uri] into the queue (≤ 50 MB) with the current options. */
    fun add(context: Context, uri: Uri, replies: Boolean, smart: Boolean, scenes: Boolean, thinking: Boolean, text: Boolean = false): Entry {
        val name = runCatching { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()?.trim()
            ?.takeIf { com.brahmadeo.supertonic.tts.books.BookFiles.validSourceName(it) } ?: "book.epub"
        val d = File(dir(context), System.currentTimeMillis().toString()).apply { mkdirs() }
        val target = File(d, name)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var size = 0L
                while (true) { val n = input.read(buf); if (n < 0) break; size += n
                    require(size <= EpubBook.MAX_BYTES) { "Файл книги больше 50 МБ" }; out.write(buf, 0, n) }
            } } ?: error("Не удалось открыть книгу")
            writeEntry(d, JSONObject().put("name", name).put("replies", replies).put("smart", smart)
                .put("scenes", scenes).put("thinking", thinking).put("text", text))
        } catch (t: Throwable) { d.deleteRecursively(); throw t }
        refresh(context); BookNightJob.schedule(context)
        return requireNotNull(mutable.value.firstOrNull { it.id == d.name })
    }

    /** Пауза подготовки из-за облака: книга [uri] (из очереди — та же запись; иначе копия [copy] или самого файла)
     * встаёт в очередь «на паузе» и продолжится сама через 1 / 5 / 15 / 30 мин, потом раз в час ([BookResumeJob]). */
    fun pause(context: Context, uri: Uri, copy: File?, replies: Boolean, smart: Boolean, scenes: Boolean, text: Boolean,
              thinking: Boolean, reason: String): Entry {
        val queued = queued(context, uri)
        val d = queued ?: File(dir(context), System.currentTimeMillis().toString()).apply { mkdirs() }
        val o = if (queued != null) readEntry(d) else {
            val name = runCatching { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()?.trim()
                ?.takeIf { com.brahmadeo.supertonic.tts.books.BookFiles.validSourceName(it) } ?: "book.epub"
            try {
                val target = File(d, name)
                if (copy != null && copy.isFile) copy.copyTo(target, overwrite = true)
                else context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: error("Не удалось открыть книгу")
            } catch (t: Throwable) { d.deleteRecursively(); throw t }
            JSONObject().put("name", name).put("replies", replies).put("smart", smart).put("scenes", scenes)
                .put("thinking", thinking).put("text", text)
        }
        val pauses = o.optInt("pauses") + 1
        val delay = PAUSE_STEPS_MIN.getOrElse(pauses - 1) { PAUSE_STEPS_MIN.last() } * 60_000L
        o.put("paused", reason.ifBlank { "облако недоступно" }).put("resume_at", System.currentTimeMillis() + delay).put("pauses", pauses)
        writeEntry(d, o)
        refresh(context)
        BookResumeJob.schedule(context)
        return requireNotNull(mutable.value.firstOrNull { it.id == d.name })
    }
    // Короткий сбой сети проходит быстро: первая попытка через минуту, дальше реже (лимит/кончились токены).
    private val PAUSE_STEPS_MIN = listOf(1L, 5L, 15L, 30L, 60L)

    /** Книга на паузе, чья очередь продолжить настала (или любая — [now]): запускает её. */
    fun resumePaused(context: Context, now: Boolean = false): Boolean {
        if (BookPreparation.state.value.running || com.brahmadeo.supertonic.tts.ambience.AmbienceGeneration.state.value.running) return false
        val next = refresh(context).filter { it.paused && (now || it.resumeAt <= System.currentTimeMillis()) }.minByOrNull { it.resumeAt } ?: return false
        val source = runCatching { BookPreparation.source(context).withThinking(next.thinking) }.getOrNull() ?: return false
        return launch(context, next, source)
    }

    fun remove(context: Context, id: String) {
        File(dir(context), id).deleteRecursively(); refresh(context)
        BookNightJob.schedule(context); BookResumeJob.schedule(context)
    }

    fun inNight(now: Calendar = Calendar.getInstance()) = now.get(Calendar.HOUR_OF_DAY) in NIGHT_FROM until NIGHT_TO

    /** Starts the next book (night rules unless [now]); false when the queue is empty, LLM is not set up or busy. */
    fun startNext(context: Context, now: Boolean = false): Boolean {
        if (BookPreparation.state.value.running) return false
        if (!now && (!inNight() || !com.brahmadeo.supertonic.tts.ambience.NpuNightJob.charging(context))) return false
        // Never beside the generator's own work (an NPU compile holds the chip and several GB).
        if (com.brahmadeo.supertonic.tts.ambience.AmbienceGeneration.state.value.running) return false
        // Книги на паузе ждут своего времени (BookResumeJob); «Подготовить сейчас» берёт и их.
        val next = refresh(context).firstOrNull { it.failures < MAX_FAILURES && (now || !it.paused) } ?: return false
        val source = runCatching { BookPreparation.source(context).withThinking(next.thinking) }.getOrNull() ?: return false
        return launch(context, next, source)
    }

    /** After the night NPU compile: the queue continues if it is still night and the phone charges. */
    fun resume(context: Context) {
        if (!nightOn(context) || BookPreparation.state.value.running) return
        Handler(Looper.getMainLooper()).postDelayed({ if (!startNext(context)) BookNightJob.schedule(context) }, 3000)
    }

    @Volatile private var running: String? = null
    @Volatile private var chain = false
    internal fun isRunning(id: String) = running == id

    @Synchronized private fun launch(context: Context, next: Entry, source: BookPreparation.Source): Boolean {
        if (BookPreparation.state.value.running) return false
        val previous = running
        return try {
            // Persist before starting the service: execute() may run before start() returns to us.
            val directory = File(dir(context), next.id)
            writeEntry(directory, readEntry(directory).put("chain", chain))
            running = next.id
            BookPreparation.start(context, Uri.fromFile(next.file), source, replies = next.replies,
                smart = next.smart, scenes = next.scenes, text = next.text).also { if (!it) running = previous }
        } catch (_: Exception) { running = previous; false }
    }

    /** Called before the service's lazy worker starts, including START_REDELIVER_INTENT in a new process. */
    internal fun started(context: Context, uri: Uri) {
        val directory = queued(context, uri) ?: return
        if (running != directory.name) {
            running = directory.name
            chain = runCatching { readEntry(directory).optBoolean("chain") }.getOrDefault(false)
        }
    }
    /** «Подготовить сейчас»: the whole queue now, one book after another, whatever the time. */
    fun startAll(context: Context): Boolean { chain = true; return startNext(context, now = true).also { if (!it) chain = false } }

    /** From BookPreparation when a run ends: a queued book that was prepared leaves the queue, a failed one counts the
     * failure (twice — it stays, skipped, for the user to see); then the next one, while the night lasts. */
    internal fun finished(context: Context, uri: Uri, prepared: Boolean, paused: Boolean = false) {
        // Пауза облака — не неудача книги: счётчик ошибок не растёт, продолжение по таймеру.
        val directory = queued(context, uri)
        if (paused) {
            if (running == directory?.name) running = null
            chain = false
            BookResumeJob.schedule(context)
            return
        }
        // The source URI survives process death; a RAM-only running id does not.
        if (directory == null) { BookResumeJob.schedule(context); return }
        running = null
        val d = directory
        if (prepared) d.deleteRecursively() else runCatching {
            // Ошибка самой книги (не облака): пауза снимается, книга остаётся с отметкой неудачи.
            val o = readEntry(d); o.remove("paused")
            writeEntry(d, o.put("failures", o.optInt("failures") + 1))
        }
        refresh(context)
        BookResumeJob.schedule(context)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!startNext(context, now = chain)) { chain = false; BookNightJob.schedule(context) }
        }, 3000)
    }
}

/** Charging + idle, network; started at night only (a job before 00:00 waits until then, one after 06:00 till the
 * next night). */
class BookNightJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        // The NPU compile first (the books' sounds then run on the NPU); the queue resumes when it ends.
        if (BookQueue.inNight() && com.brahmadeo.supertonic.tts.ambience.NpuNightJob.pending(this) &&
            !BookPreparation.state.value.running && com.brahmadeo.supertonic.tts.ambience.AmbienceGeneration.startNpuCompile(this)) {
            jobFinished(params, false); return false
        }
        val started = BookQueue.inNight() && BookQueue.startNext(this)
        jobFinished(params, false)
        if (!started) schedule(this)
        return false
    }
    override fun onStopJob(params: JobParameters?) = true

    companion object {
        private const val ID = 4210
        fun schedule(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java) ?: return
            val waiting = BookQueue.nightOn(context) && BookQueue.refresh(context).isNotEmpty()
            jobs.cancel(ID)
            if (!waiting) return
            val now = Calendar.getInstance()
            val delay = if (BookQueue.inNight(now)) 0L else Calendar.getInstance().apply {
                if (get(Calendar.HOUR_OF_DAY) >= BookQueue.NIGHT_TO) add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, BookQueue.NIGHT_FROM); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 5)
            }.timeInMillis - now.timeInMillis
            jobs.schedule(JobInfo.Builder(ID, ComponentName(context, BookNightJob::class.java))
                .setMinimumLatency(delay.coerceAtLeast(0)).setRequiresCharging(true).setRequiresDeviceIdle(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
        }
    }
}

/** Продолжение подготовки после паузы облака: когда настало время ближайшей книги «на паузе» и есть сеть. Без условий
 * зарядки и ночи — это не ночная очередь, а прерванная работа. */
class BookResumeJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val due = BookQueue.refresh(this).any { it.paused && it.resumeAt <= System.currentTimeMillis() }
        val started = due && BookQueue.resumePaused(this)
        jobFinished(params, false)
        if (!started) {
            // Android мог не разрешить фоновый запуск (экономия заряда): напоминаем уведомлением и пробуем позже.
            if (due && !BookPreparation.state.value.running) remind(this)
            schedule(this, minDelayMs = 15 * 60_000L)
        } else schedule(this)
        return false
    }

    private fun remind(context: Context) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        manager.createNotificationChannel(android.app.NotificationChannel("book_resume", "Подготовка книг",
            android.app.NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(context, 0,
            android.content.Intent(context, com.brahmadeo.supertonic.tts.BooksActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        runCatching { manager.notify(4213, androidx.core.app.NotificationCompat.Builder(context, "book_resume")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("MyTTS: подготовка книги на паузе")
            .setContentText("Облако снова может быть доступно — нажмите, чтобы продолжить").setContentIntent(open)
            .setAutoCancel(true).build()) }
    }
    override fun onStopJob(params: JobParameters?) = true

    companion object {
        private const val ID = 4212
        fun schedule(context: Context, minDelayMs: Long = 60_000L) {
            val jobs = context.getSystemService(JobScheduler::class.java) ?: return
            jobs.cancel(ID)
            // A resumed entry keeps its pause metadata until it succeeds or pauses again. Do not schedule it twice.
            val next = BookQueue.refresh(context).filter { it.paused && !BookQueue.isRunning(it.id) }.minOfOrNull { it.resumeAt } ?: return
            jobs.schedule(JobInfo.Builder(ID, ComponentName(context, BookResumeJob::class.java))
                .setMinimumLatency((next - System.currentTimeMillis()).coerceAtLeast(minDelayMs))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(false).build())
        }
    }
}
