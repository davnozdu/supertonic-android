package com.brahmadeo.supertonic.tts.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Background preparation of text already submitted by any Android TTS client. */
object LlmPreparation {
    data class Result(val text: String, val provider: String, val elapsedMs: Long, val fallback: Boolean)
    private data class Entry(val id: Long, val caller: Any, val text: String, val input: String,
        val future: CompletableFuture<Result> = CompletableFuture(), var processing: Boolean = false, var claimed: Boolean = false)
    private val lock = Any()
    private val entries = linkedMapOf<Long, Entry>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "LLM-preparation").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "LLM-idle").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)
    private var nextId = 0L
    @Volatile private var context: Context? = null
    @Volatile private var epoch = 0L
    @Volatile private var activeBatch: List<Entry> = emptyList()
    private val cooldown = mutableMapOf<String, Long>() // Only the worker accesses this.
    private val appCaller = Any()
    @Synchronized fun initialize(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext
        timer.scheduleWithFixedDelay({ context?.let { executor.execute { LlmProviders.unloadIfIdle(it) } } }, 15, 15, TimeUnit.SECONDS)
    }
    fun enabled(ctx: Context) = LlmSettings.load(ctx).let { it.mode != LlmMode.OFF && (it.stress || it.punctuation) }
    fun settingsChanged() {
        synchronized(lock) { epoch++; entries.values.forEach { it.future.cancel(false) }; entries.clear() }
        LlmProviders.cancelActive()
        executor.execute { cooldown.clear(); LlmProviders.unload() }
    }
    fun submit(ctx: Context, caller: Any, text: String, flush: Boolean = false): Long? {
        initialize(ctx)
        if (!enabled(ctx) || text.isBlank() || !text.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' }) return null
        val id = synchronized(lock) {
            if (flush) cancelLocked(caller)
            // Bound copied text, even if a reader submits an entire book.
            while (entries.isNotEmpty() && (entries.size >= 256 || entries.values.sumOf { it.text.length } + text.length > 96_000)) {
                val victim = entries.values.firstOrNull { !it.claimed && !it.processing } ?: return null
                entries.remove(victim.id); victim.future.cancel(false)
            }
            if (text.length > 6000) return null
            val entry = Entry(++nextId, caller, text, com.brahmadeo.supertonic.tts.utils.LexiconManager.apply(text))
            entries[entry.id] = entry
            entry.id
        }
        timer.schedule({ startWorker() }, 120, TimeUnit.MILLISECONDS)
        return id
    }
    fun rejected(id: Long?) { synchronized(lock) { entries.remove(id)?.future?.cancel(false) } }
    private fun cancelLocked(caller: Any) {
        val keys = entries.values.filter { it.caller == caller }.map { it.id }
        keys.forEach { entries.remove(it)?.future?.cancel(false) }
    }
    fun cancel(caller: Any) {
        synchronized(lock) { cancelLocked(caller) }
        if (activeBatch.isNotEmpty() && activeBatch.all { it.future.isCancelled }) LlmProviders.cancelActive()
    }
    fun prefetch(ctx: Context, texts: List<String>): List<Long?> = texts.map { submit(ctx, appCaller, it) }
    fun cancelApp() = cancel(appCaller)
    fun prepare(ctx: Context, text: String, id: Long? = null, timeoutMs: Long = 30_000): String {
        if (!enabled(ctx)) return text
        initialize(ctx)
        val entry = synchronized(lock) {
            (if (id != null) entries[id] else entries.values.firstOrNull { it.text == text && !it.claimed })?.also { it.claimed = true }
        } ?: submit(ctx, appCaller, text)?.let { token -> synchronized(lock) { entries[token]?.also { it.claimed = true } } }
        if (entry == null) return text
        startWorker()
        return try {
            val result = entry.future.get(timeoutMs, TimeUnit.MILLISECONDS)
            Log.i("LlmPreparation", "Delivered chars=${text.length}, provider=${result.provider}, fallback=${result.fallback}, preparationMs=${result.elapsedMs}")
            result.text
        } catch (_: Exception) {
            Log.w("LlmPreparation", "Preparation deadline/cancellation; dictionary fallback chars=${text.length}")
            text
        } finally { synchronized(lock) { entries.remove(entry.id) } }
    }
    fun test(ctx: Context, c: LlmConfig, text: String): Result {
        initialize(ctx)
        // Use the same single worker as normal reading and idle unload.
        return executor.submit<Result> { process(ctx, c, listOf(text)).single() }.get(90, TimeUnit.SECONDS)
    }
    private fun startWorker() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (true) {
                    val batchEpoch = epoch
                    val batch = synchronized(lock) {
                        val first = entries.values.firstOrNull { !it.processing && !it.future.isDone } ?: return@execute
                        var count = 0
                        entries.values.filter { it.caller == first.caller && !it.processing && !it.future.isDone }
                            .takeWhile { count += it.text.length; count <= 4000 || count == it.text.length }
                            .onEach { it.processing = true }
                    }
                    val ctx = context ?: return@execute
                    activeBatch = batch
                    val results = process(ctx, LlmSettings.load(ctx), batch.map { it.input }, batchEpoch)
                    activeBatch = emptyList()
                    if (batchEpoch == epoch) batch.zip(results).forEach { (entry, result) -> entry.future.complete(result) }
                    else batch.forEach { it.future.cancel(false) }
                }
            } finally {
                activeBatch = emptyList()
                running.set(false)
                if (synchronized(lock) { entries.values.any { !it.processing && !it.future.isDone } }) startWorker()
            }
        }
    }
    private fun connected(ctx: Context): Boolean = runCatching {
        val manager = ctx.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return@runCatching false
        manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)
    private fun process(ctx: Context, c: LlmConfig, texts: List<String>, expectedEpoch: Long = epoch): List<Result> {
        val started = SystemClock.elapsedRealtime()
        val providers = when (c.mode) {
            LlmMode.OFF -> emptyList()
            LlmMode.LOCAL -> listOf("local")
            LlmMode.OLLAMA -> listOf("ollama", "local")
            LlmMode.GEMINI -> listOf("gemini", "local")
            LlmMode.AUTO -> (if (c.preferGemini) listOf("gemini", "ollama") else listOf("ollama", "gemini")) + "local"
        }
        for (provider in providers) {
            if (expectedEpoch != epoch) break
            if (provider != "local" && !connected(ctx)) continue
            if (provider == "ollama" && c.ollamaModel.isBlank()) continue
            if (provider == "gemini" && (c.geminiKey.isBlank() || c.geminiModel.isBlank())) continue
            if (provider == "local" && !LocalModelDownload.ready(ctx)) continue
            if (SystemClock.elapsedRealtime() < (cooldown[provider] ?: 0L)) continue
            try {
                val output = if (provider == "local") LlmProviders.local(ctx, c, texts) else LlmProviders.cloud(c, texts, provider == "gemini")
                val validated = texts.zip(output).map { (a, b) -> PreparedTextValidator.validate(a, b, c.punctuation, c.stress)
                    ?: throw IllegalArgumentException("LLM изменила слова или вернула неправильные ударения") }
                val elapsed = SystemClock.elapsedRealtime() - started
                Log.i("LlmPreparation", "Prepared fragments=${texts.size}, chars=${texts.sumOf { it.length }}, provider=$provider, ms=$elapsed")
                return validated.map { Result(it, provider, elapsed, false) }
            } catch (_: Exception) {
                cooldown[provider] = SystemClock.elapsedRealtime() + 60_000
                Log.w("LlmPreparation", "Provider $provider failed; trying fallback")
            } catch (_: LinkageError) {
                cooldown[provider] = SystemClock.elapsedRealtime() + 60_000
                Log.w("LlmPreparation", "Local runtime unsupported; dictionary fallback")
            }
        }
        return texts.map { Result(it, "словарь", SystemClock.elapsedRealtime() - started, true) }
    }
}
