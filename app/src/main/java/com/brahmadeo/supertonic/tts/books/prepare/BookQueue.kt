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
                     val thinking: Boolean, val failures: Int)
    const val NIGHT_KEY = "book_queue_night"
    const val NIGHT_FROM = 0
    const val NIGHT_TO = 6
    private const val MAX_FAILURES = 2
    private val mutable = MutableStateFlow<List<Entry>>(emptyList())
    val entries = mutable.asStateFlow()

    private fun dir(context: Context) = File(context.filesDir, "book-queue")
    private fun prefs(context: Context) = context.getSharedPreferences("book_preparation", 0)
    fun nightOn(context: Context) = prefs(context).getBoolean(NIGHT_KEY, false)
    fun setNight(context: Context, on: Boolean) { prefs(context).edit().putBoolean(NIGHT_KEY, on).apply(); BookNightJob.schedule(context) }

    fun refresh(context: Context): List<Entry> {
        val list = dir(context).listFiles()?.filter { it.isDirectory }?.mapNotNull { d -> runCatching {
            val o = JSONObject(File(d, "entry.json").readText())
            val file = File(d, o.getString("name")).takeIf { it.isFile } ?: return@runCatching null
            Entry(d.name, o.getString("name"), file, o.optBoolean("replies", true), o.optBoolean("smart"), o.optBoolean("scenes"),
                o.optBoolean("thinking", true), o.optInt("failures"))
        }.getOrNull() }?.sortedBy { it.id }.orEmpty()
        mutable.value = list
        return list
    }

    /** Copies [uri] into the queue (≤ 50 MB) with the current options. */
    fun add(context: Context, uri: Uri, replies: Boolean, smart: Boolean, scenes: Boolean, thinking: Boolean): Entry {
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
            File(d, "entry.json").writeText(JSONObject().put("name", name).put("replies", replies).put("smart", smart)
                .put("scenes", scenes).put("thinking", thinking).toString())
        } catch (t: Throwable) { d.deleteRecursively(); throw t }
        refresh(context); BookNightJob.schedule(context)
        return requireNotNull(mutable.value.firstOrNull { it.id == d.name })
    }

    fun remove(context: Context, id: String) { File(dir(context), id).deleteRecursively(); refresh(context); BookNightJob.schedule(context) }

    fun inNight(now: Calendar = Calendar.getInstance()) = now.get(Calendar.HOUR_OF_DAY) in NIGHT_FROM until NIGHT_TO

    /** Starts the next book (night rules unless [now]); false when the queue is empty, LLM is not set up or busy. */
    fun startNext(context: Context, now: Boolean = false): Boolean {
        if (!now && (!inNight() || !com.brahmadeo.supertonic.tts.ambience.NpuNightJob.charging(context))) return false
        val next = refresh(context).firstOrNull { it.failures < MAX_FAILURES } ?: return false
        val source = runCatching { BookPreparation.source(context).withThinking(next.thinking) }.getOrNull() ?: return false
        return runCatching { BookPreparation.start(context, Uri.fromFile(next.file), source, replies = next.replies, smart = next.smart, scenes = next.scenes) }
            .getOrDefault(false).also { if (it) running = next.id }
    }

    @Volatile private var running: String? = null
    @Volatile private var chain = false
    /** «Подготовить сейчас»: the whole queue now, one book after another, whatever the time. */
    fun startAll(context: Context): Boolean { chain = true; return startNext(context, now = true).also { if (!it) chain = false } }

    /** From BookPreparation when a run ends: a queued book that was prepared leaves the queue, a failed one counts the
     * failure (twice — it stays, skipped, for the user to see); then the next one, while the night lasts. */
    internal fun finished(context: Context, uri: Uri, prepared: Boolean) {
        val id = running ?: return
        if (uri.scheme != "file" || uri.path?.contains("/book-queue/$id/") != true) return
        running = null
        val d = File(dir(context), id)
        if (prepared) d.deleteRecursively() else runCatching {
            val f = File(d, "entry.json"); val o = JSONObject(f.readText()); f.writeText(o.put("failures", o.optInt("failures") + 1).toString())
        }
        refresh(context)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!startNext(context, now = chain)) { chain = false; BookNightJob.schedule(context) }
        }, 3000)
    }
}

/** Charging + idle, network; started at night only (a job before 00:00 waits until then, one after 06:00 till the
 * next night). */
class BookNightJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
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
