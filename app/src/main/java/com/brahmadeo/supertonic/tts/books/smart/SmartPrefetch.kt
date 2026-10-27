package com.brahmadeo.supertonic.tts.books.smart

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.brahmadeo.supertonic.tts.books.prepare.BookPreparation
import com.brahmadeo.supertonic.tts.llm.LlmProviders
import java.util.concurrent.Executors

/** «Готовить пересказ заранее»: while a recognised book is being read, the short content of the chapters already read is
 * made in the background, so «Ранее в книге» is then one request. Only ever started by reading (the text a reader sends
 * to the engine): an idle phone does nothing — no alarms, no jobs. At most one request in [GAP_MS], only chapters
 * behind the place being read (never ahead), never while a retelling is being made. */
object SmartPrefetch {
    const val PREF_ON = "smart_book_prefetch"
    const val PREF_WIFI = "smart_book_prefetch_wifi"
    private const val GAP_MS = 10 * 60 * 1000L
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "SmartPrefetch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var lastAt = 0L
    @Volatile private var busy = false

    private fun prefs(context: Context) = context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean(PREF_ON, true)
    fun wifiOnly(context: Context) = prefs(context).getBoolean(PREF_WIFI, false)

    /** Called with the place being read (on the matcher's thread): cheap checks only, the work goes to its own thread. */
    fun reading(context: Context, position: SmartMatcher.Position) {
        val now = System.currentTimeMillis()
        if (busy || now - lastAt < GAP_MS || !enabled(context) || SmartRecap.state.value.running) return
        if (!network(context)) return
        lastAt = now; busy = true
        val ctx = context.applicationContext
        executor.execute {
            try { prepare(ctx, position) } catch (_: Exception) { } finally { busy = false }
        }
    }

    private fun network(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !wifiOnly(context) || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** One pack: the chapters read without a summary, nearest to the place first. */
    private fun prepare(ctx: Context, position: SmartMatcher.Position) {
        val text = SmartLibrary.text(ctx, position.key) ?: return
        val current = text.sectionOf(position.paragraph)
        val have = text.summaries
        val missing = (0 until current).filter { it !in have && text.sectionText(it).isNotBlank() }
        if (missing.isEmpty()) return
        val pack = SmartPrompts.packs(missing.reversed().map { it to text.sectionText(it).length.coerceAtMost(SmartPrompts.PACK_CHARS) }).first()
        val source = runCatching { BookPreparation.source(ctx).withThinking(false) }.getOrNull() ?: return
        val cancel = LlmProviders.CloudCancellation()
        SmartPrompts.summarize(text, pack.sorted(), have, { prompt, tokens, multi -> SmartRecap.request(source, prompt, tokens, cancel, multi) },
            { _, _ -> }, { section, summary -> SmartLibrary.saveSummary(ctx, position.key, section, summary) })
    }
}
