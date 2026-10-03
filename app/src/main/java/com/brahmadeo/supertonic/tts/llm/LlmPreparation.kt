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
    data class Result(val text: String, val provider: String, val elapsedMs: Long, val fallback: Boolean, val reason: String? = null)
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
    private val preparedCache = LlmTextCache<LlmConfig>()
    private var ambiguousLocalYo: Set<String> = setOf("все","узнает","берет")
    private val russianNumbers = com.brahmadeo.supertonic.tts.utils.RussianNumberNormalizer()
    @Synchronized fun initialize(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext
        ambiguousLocalYo=runCatching { ctx.assets.open("tera_ambiguous_yo.txt").bufferedReader().use { it.readLines().toSet() } }.getOrDefault(ambiguousLocalYo)
        timer.scheduleWithFixedDelay({ context?.let { executor.execute { LlmProviders.unloadIfIdle(it) } } }, 15, 15, TimeUnit.SECONDS)
    }
    fun enabled(ctx: Context) = LlmSettings.enabled(ctx)
    fun settingsChanged() {
        synchronized(lock) { epoch++; entries.values.forEach { it.future.cancel(false) }; entries.clear() }
        preparedCache.clear()
        LlmProviders.cancelActive()
        executor.execute { cooldown.clear(); LlmProviders.unload() }
    }
    fun submit(ctx: Context, caller: Any, text: String, flush: Boolean = false): Long? {
        if (flush) cancel(caller)
        initialize(ctx)
        if (text.length > 6000 || !enabled(ctx) || text.isBlank() || !text.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' }) return null
        val id = synchronized(lock) {
            // Bound copied text, even if a reader submits an entire book.
            while (entries.isNotEmpty() && (entries.size >= 256 || entries.values.sumOf { it.text.length } + text.length > 96_000)) {
                val victim = entries.values.firstOrNull { it.future.isDone || (!it.claimed && !it.processing) } ?: return null
                entries.remove(victim.id); victim.future.cancel(false)
            }
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
        // prepare() may already have removed a timed-out claimed entry.
        activeBatch.filter { it.caller == caller }.forEach { it.future.cancel(false) }
    }
    fun cancel(caller: Any) {
        synchronized(lock) { cancelLocked(caller) }
        val batch = activeBatch
        if (batch.any { it.caller == caller } && batch.all { it.future.isDone }) LlmProviders.cancelActive()
    }
    fun prefetch(ctx: Context, texts: List<String>): List<Long?> = texts.map { submit(ctx, appCaller, it) }
    fun cancelApp() = cancel(appCaller)
    fun prepare(ctx: Context, text: String, id: Long? = null, timeoutMs: Long = 1500): String = prepareResult(ctx,text,id,timeoutMs).text
    fun prepareResult(ctx: Context, text: String, id: Long? = null, timeoutMs: Long = 1500): Result {
        if (!enabled(ctx)) return Result(text,"автономно",0,true)
        initialize(ctx)
        val entry = synchronized(lock) {
            (if (id != null) entries[id] else entries.values.firstOrNull { it.text == text && !it.claimed }
                ?: entries.values.firstOrNull { it.text == text })?.also { it.claimed = true }
        } ?: submit(ctx, appCaller, text)?.let { token -> synchronized(lock) { entries[token]?.also { it.claimed = true } } }
        if (entry == null) return Result(text,"словарь",0,true,"Текст не принят в очередь LLM")
        startWorker()
        return try {
            val result = entry.future.get(timeoutMs, TimeUnit.MILLISECONDS)
            Log.i("LlmPreparation", "Delivered chars=${text.length}, provider=${result.provider}, fallback=${result.fallback}, preparationMs=${result.elapsedMs}")
            synchronized(lock) { entries.remove(entry.id) }
            result
        } catch (_: Exception) {
            Log.w("LlmPreparation", "Preparation not ready within ${timeoutMs}ms; dictionary fallback chars=${text.length}")
            // Keep the future even if it completed just after get() timed out:
            // the background waiter can still consume the validated result.
            Result(text,"словарь",timeoutMs,true,"LLM не успела ответить")
        }
    }
    fun test(ctx: Context, c: LlmConfig, text: String, traceSynthetic: Boolean = false): Result {
        initialize(ctx)
        // Use the same single worker as normal reading and idle unload.
        return executor.submit<Result> { process(ctx, c, listOf(text), ignoreCooldown = true, traceSynthetic = traceSynthetic).single() }.get(90, TimeUnit.SECONDS)
    }
    private fun startWorker() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (true) {
                    val batchEpoch = epoch
                    val ctx = context ?: return@execute
                    val config = LlmSettings.load(ctx)
                    val batchLimit = if (config.mode == LlmMode.LOCAL) 1000 else 4000
                    val batch = synchronized(lock) {
                        val first = entries.values.firstOrNull { !it.processing && !it.future.isDone } ?: return@execute
                        var count = 0
                        entries.values.filter { it.caller == first.caller && !it.processing && !it.future.isDone }
                            .takeWhile { count += it.text.length; count <= batchLimit || count == it.text.length }
                            .onEach { it.processing = true }
                    }
                    activeBatch = batch
                    val results = process(ctx, config, batch.map { it.input }, batchEpoch,
                        cancelled = { batch.all { it.future.isDone } },
                        onPrepared = { index, result -> if (batchEpoch == epoch) batch[index].future.complete(result) })
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
    private fun process(ctx: Context, c: LlmConfig, texts: List<String>, expectedEpoch: Long = epoch,
                        ignoreCooldown: Boolean = false, traceSynthetic: Boolean = false, cancelled: () -> Boolean = { false },
                        onPrepared: (Int, Result) -> Unit = { _, _ -> }): List<Result> {
        val started = SystemClock.elapsedRealtime()
        val results = arrayOfNulls<Result>(texts.size)
        preparedCache.get(c, texts)?.let { cached ->
            if (expectedEpoch == epoch && !cancelled()) {
                cached.forEachIndexed { index, value -> if (value != null) {
                    val result = Result(value, "кэш", 0, false)
                    results[index] = result; onPrepared(index, result)
                } }
                Log.i("LlmPreparation", "Cache hit fragments=${cached.count { it != null }}/${texts.size}, chars=${texts.sumOf { it.length }}")
                if (results.all { it != null }) return results.map { it!! }
            }
        }
        var failure: String? = null
        val numericInputs = texts.map { com.brahmadeo.supertonic.tts.foreign.ForeignText.prepareNumbers(it, russianNumbers) }
        val providerTexts = numericInputs.map { it.text }
        val providers = when (c.mode) {
            LlmMode.OFF -> emptyList()
            LlmMode.LOCAL -> listOf("local")
            LlmMode.OLLAMA -> listOf("ollama", "ollama", "local")
            LlmMode.GEMINI -> listOf("gemini", "gemini", "local")
            LlmMode.AUTO -> (if (c.preferGemini) listOf("gemini", "gemini", "ollama", "ollama") else listOf("ollama", "ollama", "gemini", "gemini")) + "local"
        }
        for (provider in providers) {
            if (expectedEpoch != epoch || cancelled()) break
            if (provider != "local" && !connected(ctx)) continue
            if (provider == "ollama" && c.ollamaModel.isBlank()) continue
            if (provider == "gemini" && (c.geminiKey.isBlank() || c.geminiModel.isBlank())) continue
            if (provider == "local" && !LocalModelDownload.ready(ctx)) continue
            if (!ignoreCooldown && SystemClock.elapsedRealtime() < (cooldown[provider] ?: 0L)) continue
            val requestIndices = LlmRetryContext.indices(providerTexts, results.indices.filter { results[it] == null }, if (provider == "local") 1600 else 8000)
            val requestTexts = requestIndices.map { providerTexts[it] }
            if (requestTexts.sumOf { it.length } > if (provider == "local") 1600 else 8000) {
                failure = "Текст после раскрытия чисел превышает лимит LLM; используется словарь"
                continue // A large block must not put the provider into cooldown.
            }
            try {
                var accepted = 0
                fun accept(requestIndex: Int, proposed: String) {
                    if (expectedEpoch != epoch || cancelled()) return
                    val index = requestIndices[requestIndex]
                    if (results[index] != null) return
                    val source = requestTexts[requestIndex]
                    var rejection = "structure"
                    val validated = PreparedTextValidator.validate(source, proposed, c.punctuation, c.stress,
                        numericInputs[index].ranges, c.restoreYo, requireStress=provider!="local" && c.stress) { rejection = it }
                    if (traceSynthetic) Log.i("SpeechCheck","SYNTHETIC PROPOSAL provider=$provider: $proposed")
                    if (validated != null) {
                        val completed=if(provider=="local" && (c.stress || c.restoreYo)) {
                            val local=com.brahmadeo.supertonic.tts.local.LocalRussianStress.apply(ctx,validated)
                            val dictionary=com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.apply(local,"ru")
                            val safe=com.brahmadeo.supertonic.tts.utils.RussianYoPolicy.apply(validated,dictionary,c.restoreYo)
                            MissingSpeechMarks.merge(validated,safe,c.stress,c.restoreYo,ambiguousLocalYo)
                        } else validated
                        val supplemented=completed!=validated
                        if(provider=="local") Log.i("LlmPreparation","Local supplement chars=${validated.length} llmEdited=${validated!=source} llmStress=${validated.count { it=='\u0301' }} llmYoAdded=${validated.count { it in "ёЁ" }-source.count { it in "ёЁ" }} changed=$supplemented; explicit LLM stress/yo retained")
                        val result = Result(completed, if(supplemented) "local+offline" else provider, SystemClock.elapsedRealtime()-started, false)
                        results[index] = result; accepted++
                        onPrepared(index, result)
                        if (provider == "local") preparedCache.put(c,texts,results.map { it?.text })
                    } else Log.w("LlmPreparation", "Rejected fragment=$index, chars=${source.length}, provider=$provider, reason=$rejection")
                }
                if (provider == "local") {
                    LlmProviders.local(ctx,c,requestTexts,deadlineMs=45000,onOutput=::accept)
                } else {
                    LlmProviders.cloud(c,requestTexts,provider=="gemini").forEachIndexed { index,text -> accept(index,text) }
                }
                if (expectedEpoch != epoch || cancelled()) break
                val elapsed = SystemClock.elapsedRealtime()-started
                Log.i("LlmPreparation", "Prepared fragments=$accepted/${texts.size}, requested=${requestIndices.size}, remaining=${results.count { it == null }}, chars=${texts.sumOf { it.length }}, provider=$provider, ms=$elapsed")
                if (expectedEpoch == epoch) preparedCache.put(c, texts, results.map { it?.text })
                if (results.all { it != null }) {
                    val complete = results.map { it!! }
                    // Cache only validated successes, retaining the whole context.
                    return complete
                }
                failure = "LLM изменила слова или вернула неправильные ударения в части фрагментов"
                if (accepted == 0 && results.all { it == null }) cooldown[provider] = SystemClock.elapsedRealtime() + 60_000
            } catch (e: Exception) {
                if (expectedEpoch != epoch || cancelled()) break
                val detail = e.message.orEmpty()
                failure = if (listOf("LLM ", "API HTTP", "Сначала ", "Выберите ", "Подготовка ").any { detail.startsWith(it) }) detail.take(180)
                    else e.javaClass.simpleName + (Regex("Status Code: \\d+").find(detail)?.value?.let { ": $it" } ?: "")
                cooldown[provider] = SystemClock.elapsedRealtime() + 60_000
                Log.w("LlmPreparation", "Provider $provider failed: $failure; trying fallback")
            } catch (_: LinkageError) {
                failure = "Локальная среда выполнения не поддерживается"
                cooldown[provider] = SystemClock.elapsedRealtime() + 60_000
                Log.w("LlmPreparation", "Local runtime unsupported; dictionary fallback")
            }
        }
        return texts.mapIndexed { index, text -> results[index] ?: Result(text, "словарь", SystemClock.elapsedRealtime() - started, true,
            failure ?: "Нет готового провайдера: проверьте выбранную модель, ключ и скачивание Gemma") }
    }
}
