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

private typealias RoleRequest = (String, List<String>, String) -> List<List<VoiceRoleText>?>

/** Background preparation of text already submitted by any Android TTS client. */
object LlmPreparation {
    data class Result(val text: String, val provider: String, val elapsedMs: Long, val fallback: Boolean, val reason: String? = null, val voicePlan: List<VoiceRoleText> = emptyList(), val rolesReady: Boolean = false, val roleProvider: String? = null)
    private data class Entry(val id: Long, val caller: Any, val text: String, val input: String,
        val future: CompletableFuture<Result> = CompletableFuture(), var processing: Boolean = false, var claimed: Boolean = false, @Volatile var cancelled: Boolean = false, @Volatile var textReady: Result? = null)
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
    private val playedResults = LlmResultCache<Result> { input, result -> input.length + result.text.length }
    private val roleContext = VoiceRoleContext()
    private data class RoleKey(val config: LlmConfig, val preceding: String, val texts: List<String>)
    private data class RoleCached(val routing: VoiceRoleRouting.Result, val time: Long)
    private val roleCache = linkedMapOf<RoleKey, RoleCached>()
    private val roleCooldown = mutableMapOf<String, Long>()
    private var roleCacheChars = 0
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
        synchronized(lock) { epoch++; entries.values.forEach { it.cancelled = true; it.future.cancel(false) }; entries.clear() }
        preparedCache.clear()
        playedResults.clear()
        roleContext.clear()
        synchronized(roleCache) { roleCache.clear(); roleCacheChars = 0 }
        LlmProviders.cancelActive()
        executor.execute { cooldown.clear(); roleCooldown.clear(); LlmProviders.unload() }
    }
    fun submit(ctx: Context, caller: Any, text: String, flush: Boolean = false): Long? {
        if (flush) cancel(caller)
        initialize(ctx)
        if (text.length > 6000 || !enabled(ctx) || text.isBlank() || !text.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' }) return null
        val input = com.brahmadeo.supertonic.tts.utils.LexiconManager.apply(text)
        val reused = playedResults.get(input)
        val id = synchronized(lock) {
            // Bound copied text, even if a reader submits an entire book.
            while (entries.isNotEmpty() && (entries.size >= 512 || entries.values.sumOf { it.text.length } + text.length > 192_000)) {
                val victim = entries.values.firstOrNull { it.future.isDone || (!it.claimed && !it.processing) } ?: return null
                entries.remove(victim.id); victim.future.cancel(false)
            }
            val entry = Entry(++nextId, caller, text, input)
            if (reused != null) { entry.textReady = reused; entry.future.complete(reused) }
            entries[entry.id] = entry
            entry.id
        }
        if (reused != null) {
            // Keep role continuity for the next paragraph, as the worker would.
            roleContext.append(caller, listOf(if (reused.voicePlan.isEmpty()) reused.text else reused.voicePlan.joinToString("") { "[${it.role.name}]${it.text}" }))
            com.brahmadeo.supertonic.tts.utils.DiagLog.i("LlmPreparation", "Reused played preparation chars=${text.length} provider=${reused.provider} roles=${reused.rolesReady} source=${SpeechTextTrace.fingerprint(text)}")
            return id
        }
        timer.schedule({ startWorker() }, 120, TimeUnit.MILLISECONDS)
        return id
    }
    /** Consume the latest result, not the immutable future completed before cloud recovery. */
    fun takeForPlayback(text: String): Result? = synchronized(lock) {
        val entry = entries.values.firstOrNull { it.text == text && !it.cancelled } ?: return@synchronized null
        val latest = entry.textReady ?: return@synchronized null
        if (!entry.future.isDone && !latest.rolesReady) return@synchronized null
        entries.remove(entry.id)
        remember(entry.input, latest)
        latest
    }
    /** Only complete LLM results: a dictionary fallback or pending roles must be retried later. */
    private fun remember(input: String, result: Result) {
        val ctx = context ?: return
        if (!result.fallback && (result.rolesReady || !LlmSettings.multiVoiceEnabled(ctx))) playedResults.put(input, result)
    }
    fun consumed(text: String) { synchronized(lock) {
        entries.values.firstOrNull { it.text == text }?.let {
            entries.remove(it.id); it.cancelled = true; it.future.cancel(false)
        }
    } }
    private fun release(entry: Entry) { synchronized(lock) {
        if (entries[entry.id] === entry) entries.remove(entry.id)
        if (!entry.future.isDone) { entry.cancelled = true; entry.future.cancel(false) }
    } }
    fun rejected(id: Long?) { synchronized(lock) { entries.remove(id)?.future?.cancel(false) } }
    private fun cancelLocked(caller: Any, clearContext: Boolean = true) {
        if (clearContext) roleContext.clear(caller)
        val keys = entries.values.filter { it.caller == caller }.map { it.id }
        keys.forEach { entries.remove(it)?.let { entry -> entry.cancelled = true; entry.future.cancel(false) } }
        // prepare() may already have removed a timed-out claimed entry.
        activeBatch.filter { it.caller == caller }.forEach { it.cancelled = true; it.future.cancel(false) }
    }
    fun cancel(caller: Any, clearContext: Boolean = true) {
        synchronized(lock) { cancelLocked(caller, clearContext) }
        val batch = activeBatch
        if (batch.any { it.caller == caller } && batch.all { it.future.isDone }) LlmProviders.cancelActive()
    }
    fun prefetch(ctx: Context, texts: List<String>): List<Long?> = texts.map { submit(ctx, appCaller, it) }
    fun cancelApp() = cancel(appCaller)
    fun pauseApp() = cancel(appCaller, clearContext = false)
    fun prepare(ctx: Context, text: String, id: Long? = null, timeoutMs: Long = 1500): String = prepareResult(ctx,text,id,timeoutMs).text
    fun prepareResult(ctx: Context, text: String, id: Long? = null, timeoutMs: Long = 1500, retainForPlayback: Boolean = false): Result {
        if (!enabled(ctx)) return Result(text,"автономно",0,true)
        initialize(ctx)
        val entry = synchronized(lock) {
            (if (id != null) entries[id] else entries.values.firstOrNull { it.text == text && !it.claimed }
                ?: entries.values.firstOrNull { it.text == text })?.also { it.claimed = true }
        } ?: submit(ctx, appCaller, text)?.let { token -> synchronized(lock) { entries[token]?.also { it.claimed = true } } }
        if (entry == null) return Result(text,"словарь",0,true,"Текст не принят в очередь LLM")
        startWorker()
        return try {
            val completed = entry.future.get(timeoutMs, TimeUnit.MILLISECONDS)
            val result = entry.textReady ?: completed
            remember(entry.input, result)
            com.brahmadeo.supertonic.tts.utils.DiagLog.i("LlmPreparation", "Delivered chars=${text.length}, provider=${result.provider}, fallback=${result.fallback}, preparationMs=${result.elapsedMs}")
            com.brahmadeo.supertonic.tts.utils.DiagLog.i("LlmPreparation", "Text trace source=${SpeechTextTrace.fingerprint(text)} prepared=${SpeechTextTrace.fingerprint(result.text)} provider=${result.provider} fallback=${result.fallback}")
            if (!retainForPlayback) release(entry)
            result
        } catch (_: Exception) {
            entry.textReady?.takeIf { !entry.cancelled && !entry.future.isCancelled }?.let {
                Log.i("MultiVoice","Roles pending; delivering validated LLM text with author voice chars=${it.text.length}")
                if (!retainForPlayback) release(entry)
                return it
            }
            if (!retainForPlayback) release(entry)
            Log.w("LlmPreparation", "Preparation not ready within ${timeoutMs}ms; dictionary fallback chars=${text.length}")
            // Keep the future even if it completed just after get() timed out:
            // the background waiter can still consume the validated result.
            Result(text,"словарь",timeoutMs,true,"LLM не успела ответить")
        }
    }
    /** Diagnostics (StressProbe): validated LLM text per source, before names, offline fill and arbitration. */
    @Volatile internal var probeRaw: MutableMap<String, String>? = null
    private val verifyPool = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "LLM-stress-check").apply { isDaemon = true } }
    /** [StressCheck]: words where the cloud LLM and the offline Silero Stress disagree are asked again, in their
     * sentence. Without the offline model, or on any failure, the LLM's own marks stay. */
    /** Returns the checked texts and the offline opinions it computed (reused to fill unmarked words, so Silero runs
     * once per batch instead of twice). */
    private fun crossCheck(ctx: Context, c: LlmConfig, outputs: List<String>, gemini: Boolean): Pair<List<String>, List<String>?> = try {
        val started = SystemClock.elapsedRealtime()
        val offline = outputs.map { com.brahmadeo.supertonic.tts.utils.RussianNames.overlay(ctx,
            com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.apply(
            com.brahmadeo.supertonic.tts.local.LocalRussianStress.apply(ctx, StressCheck.unmarked(it)), "ru")) }
        val offlineMs = SystemClock.elapsedRealtime() - started
        val conflicts = StressCheck.disputes(outputs, offline)
        // Homographs both sides stressed alike are asked too: agreement is no proof there (о́рган/орга́н).
        val disputes = (conflicts + StressCheck.homographs(outputs, conflicts) {
            com.brahmadeo.supertonic.tts.local.LocalRussianStress.homographVariants(ctx, it) }).take(40)
        val checked = if (disputes.isEmpty()) outputs
        else {
            // The same provider judges by default: on «Идиот» ch. 1 it scored 34-36/42 key words against 33/42 for a
            // Gemini judge (hard set 63/71 both), and it needs no second key or extra requests to another service.
            val hasGemini = c.geminiKey.isNotBlank() && c.geminiModel.isNotBlank()
            val hasOllama = c.ollamaModel.isNotBlank()
            val judgeGemini = when (LlmProviders.verifierOverride) {
                "GEMINI" -> hasGemini
                "OLLAMA" -> !hasOllama
                "SAME" -> gemini
                else -> gemini
            }
            val reversed = verifyPool.submit<List<Int>> { LlmProviders.verifyStress(c, StressCheck.items(disputes, offlineFirst = true), judgeGemini) }
            val direct = try { LlmProviders.verifyStress(c, StressCheck.items(disputes, offlineFirst = false), judgeGemini) }
                catch (e: Exception) { reversed.cancel(true); throw e }
            // The judge disagreeing with itself: the full dictionary decides non-homographs (when downloaded).
            val dictionaryReady = StressJudgeDictionary.ensure(ctx)
            StressCheck.apply(outputs, disputes, direct + reversed.get(30, TimeUnit.SECONDS)) { d ->
                val bare = StressCheck.bareWord(d)
                val nameOrdinal = if (bare.first().isUpperCase()) com.brahmadeo.supertonic.tts.utils.RussianNames.ordinal(bare) else null
                if (nameOrdinal != null) nameOrdinal == StressCheck.offlineOrdinal(d)
                else dictionaryReady && !com.brahmadeo.supertonic.tts.local.LocalRussianStress.isHomograph(ctx, bare) &&
                    StressJudgeDictionary.ordinal(bare).let { it != null && it == StressCheck.offlineOrdinal(d) }
            }
        }
        Log.i("LlmPreparation", "Stress cross-check judge=${if (disputes.isEmpty()) "-" else LlmProviders.lastVerifier} disputes=${disputes.size} offlineChosen=${StressCheck.lastOfflineChosen} tieBreaks=${StressCheck.lastTieBreaks} offlineMs=$offlineMs ms=${SystemClock.elapsedRealtime() - started}")
        checked to offline
    } catch (e: Exception) {
        Log.w("LlmPreparation", "Stress cross-check skipped: ${e.javaClass.simpleName}: ${e.message}")
        outputs to null
    }
    private val wordPattern = Regex("[+А-Яа-яЁё\u0301]+")
    /** Offline marks for [text]: the opinion already computed for this fragment when its words still line up with
     * the validated text, otherwise one fresh Silero pass. */
    private fun offlineMarks(ctx: Context, text: String, opinion: String?): String {
        if (opinion != null) {
            val a = wordPattern.findAll(text).map { it.value.replace("\u0301", "").replace("+", "").lowercase().replace('ё', 'е') }.toList()
            val b = wordPattern.findAll(opinion).map { it.value.replace("\u0301", "").replace("+", "").lowercase().replace('ё', 'е') }.toList()
            if (a == b) return opinion
        }
        return com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.apply(
            com.brahmadeo.supertonic.tts.local.LocalRussianStress.apply(ctx, StressCheck.unmarked(text)), "ru")
    }
    /** Opinions of the batch being accepted (cloud path), by request index. */
    @Volatile private var batchOpinions: List<String>? = null
    /** Diagnostics: one reading batch through the production path (providers, validator, repair, names). */
    internal fun testBatch(ctx: Context, c: LlmConfig, texts: List<String>): List<Result> {
        initialize(ctx)
        // A probe compares prompts and providers on the same text: never answer it from the cache.
        return executor.submit<List<Result>> { preparedCache.clear(); process(ctx, c, texts, ignoreCooldown = true) }.get(180, TimeUnit.SECONDS)
    }
    fun test(ctx: Context, c: LlmConfig, text: String, traceSynthetic: Boolean = false): Result {
        initialize(ctx)
        // Use the same single worker as normal reading and idle unload.
        return executor.submit<Result> { process(ctx, c, listOf(text), ignoreCooldown = true, traceSynthetic = traceSynthetic).single() }.get(90, TimeUnit.SECONDS)
    }
    /** Private Activity probe: inject one cloud-role outage, exercise real Gemma and
     * the production scheduled recovery, then consume the latest queued result. */
    internal fun testRoleRecovery(ctx: Context, c: LlmConfig, text: String): Pair<Result, Result> {
        require(c.multiVoice && c.mode !in listOf(LlmMode.OFF, LlmMode.LOCAL))
        initialize(ctx)
        val probeEpoch = epoch
        val entry = synchronized(lock) {
            Entry(++nextId, Any(), text, text, processing = true).also { entries[it.id] = it }
        }
        val outages = roleProviders(c).filter { it != "local" }.toMutableSet()
        var localCalls = 0
        val request: RoleRequest = { provider, parts, before ->
            if (provider != "local" && outages.remove(provider)) throw java.io.IOException("Synthetic role outage")
            if (provider == "local") localCalls++
            LlmProviders.voiceRoles(ctx, c, parts, before, provider)
        }
        try {
            val initial = executor.submit<Result> {
                val result = process(ctx, c, listOf(text), probeEpoch, ignoreCooldown = true,
                    roleRequest = request).single()
                entry.textReady = result; entry.future.complete(result)
                scheduleRoleRecovery(ctx, c, listOf(entry), "", probeEpoch, roleRequest = request)
                result
            }.get(90, TimeUnit.SECONDS)
            Log.i("SpeechCheck", "RECOVERY initialRoleProvider=${initial.roleProvider} ready=${initial.rolesReady} localCalls=$localCalls")
            if (LocalModelDownload.activeReady(ctx)) check(localCalls > 0) { "Local role fallback was not attempted" }
            val deadline = SystemClock.elapsedRealtime() + 65_000
            val preferred = roleProviders(c).first()
            while (SystemClock.elapsedRealtime() < deadline && probeEpoch == epoch) {
                val current = entry.textReady
                if (current?.rolesReady == true && current.roleProvider == preferred) break
                Thread.sleep(200)
            }
            check(entry.textReady?.roleProvider == preferred && entry.textReady?.rolesReady == true) { "Cloud roles did not recover" }
            val recovered = checkNotNull(takeForPlayback(text))
            check(VoiceRolePlan.safe(recovered.text, recovered.voicePlan) == recovered.voicePlan)
            check(entry.future.get().roleProvider == initial.roleProvider) // Immutable future stays old.
            Log.i("SpeechCheck", "RECOVERY PASSED initial=${initial.roleProvider} recovered=${recovered.roleProvider} latestConsumed=true roles=${recovered.voicePlan.map { it.role }.toSet()}")
            return initial to recovered
        } finally { release(entry) }
    }
    private fun startWorker() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (true) {
                    val batchEpoch = epoch
                    val ctx = context ?: return@execute
                    val config = LlmSettings.load(ctx)
                    // ~2400 chars return in ~5-8 s; 4000-char batches exceeded the cloud deadline
                    // on the device and put the provider into cooldown.
                    val batchLimit = if (config.mode == LlmMode.LOCAL) 1000 else 2400
                    val batch = synchronized(lock) {
                        val first = entries.values.firstOrNull { !it.processing && !it.future.isDone } ?: return@execute
                        var count = 0
                        entries.values.filter { it.caller == first.caller && !it.processing && !it.future.isDone }
                            .takeWhile { count += it.text.length; count <= batchLimit || count == it.text.length }
                            .onEach { it.processing = true }
                    }
                    activeBatch = batch
                    val preceding = roleContext.get(batch.first().caller)
                    val results = try { process(ctx, config, batch.map { it.input }, batchEpoch,
                        preceding = preceding,
                        onTextPrepared = { index, result -> if (batchEpoch == epoch) batch[index].textReady = result },
                        cancelled = { batch.all { it.cancelled } },
                        onPrepared = { index, result -> if (batchEpoch == epoch && !batch[index].cancelled) {
                            batch[index].textReady = result
                            batch[index].future.complete(result)
                        } }) } catch (e: Exception) {
                        Log.w("LlmPreparation", "Batch failed error=${e.javaClass.simpleName}; offline reading remains available")
                        batch.map { Result(it.input, "словарь", 0, true, "Ошибка фоновой обработки") }
                    } catch (e: LinkageError) {
                        Log.w("LlmPreparation", "Unsupported runtime; offline reading remains available")
                        batch.map { Result(it.input, "словарь", 0, true, "Среда выполнения недоступна") }
                    }
                    synchronized(lock) { if (batchEpoch == epoch && batch.any { !it.cancelled }) {
                        // Record processed roles too, so a continued quote can keep its voice.
                        roleContext.append(batch.first().caller, results.map { result ->
                            if (result.voicePlan.isEmpty()) result.text else result.voicePlan.joinToString("") { "[${it.role.name}]${it.text}" }
                        })
                    }
                    }
                    activeBatch = emptyList()
                    if (batchEpoch == epoch) {
                        batch.zip(results).forEach { (entry, result) ->
                            if (!entry.cancelled) { entry.textReady = result; entry.future.complete(result) }
                        }
                        if (com.brahmadeo.supertonic.tts.utils.AssetManager.isRussianModel(ctx) && needsRoleRecovery(config, results)) scheduleRoleRecovery(ctx, config, batch, preceding, batchEpoch)
                    }
                    else batch.forEach { it.future.cancel(false) }
                    return@execute
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
                        ignoreCooldown: Boolean = false, traceSynthetic: Boolean = false, preceding: String = "",
                        cancelled: () -> Boolean = { false }, onPrepared: (Int, Result) -> Unit = { _, _ -> },
                        onTextPrepared: (Int, Result) -> Unit = { _, _ -> }, roleRequest: RoleRequest? = null): List<Result> {
        val start = SystemClock.elapsedRealtime()
        val multi = c.multiVoice && c.mode != LlmMode.OFF && com.brahmadeo.supertonic.tts.utils.AssetManager.isRussianModel(ctx)
        val earlyRoles = mutableMapOf<Int, Result>()
        // A rejected word in one paragraph must not hold all accepted paragraphs
        // behind a slow local text retry (especially with the fast Silero engine).
        fun routeAcceptedBeforeRetry(partial: List<Result?>) {
            if (!multi || expectedEpoch != epoch || cancelled()) return
            val indices = partial.indices.filter { partial[it] != null && earlyRoles[it] == null }
            if (indices.isEmpty()) return
            val before = (preceding + "\n" + texts.take(indices.first()).joinToString("\n")).takeLast(1800)
            val routed = routeRoles(ctx,c,indices.map { partial[it]!! },before,expectedEpoch,ignoreCooldown,cancelled,
                resolved = { i, result -> if (result.rolesReady) { earlyRoles[indices[i]] = result; onPrepared(indices[i],result) } }, roleRequest = roleRequest)
            routed.forEachIndexed { i, result -> if (result.rolesReady) { earlyRoles[indices[i]] = result; onPrepared(indices[i],result) } }
        }
        val prepared = processText(ctx, c, texts, expectedEpoch, ignoreCooldown, traceSynthetic, cancelled,
            if (multi) onTextPrepared else onPrepared, beforeRetry = ::routeAcceptedBeforeRetry)
            .mapIndexed { i, result -> earlyRoles[i]?.takeIf { it.text == result.text } ?: result }
        if (!multi || expectedEpoch != epoch || cancelled()) return prepared
        val results = routeRoles(ctx, c, prepared, preceding, expectedEpoch, ignoreCooldown, cancelled, resolved = onPrepared, roleRequest = roleRequest)
            .map { it.copy(elapsedMs = SystemClock.elapsedRealtime() - start) }
        results.forEachIndexed { index, result -> onPrepared(index, result) }
        return results
    }
    private fun roleProviders(c: LlmConfig) = when (c.mode) {
        LlmMode.OFF -> emptyList()
        LlmMode.LOCAL -> listOf("local")
        LlmMode.OLLAMA -> listOf("ollama", "local")
        LlmMode.GEMINI -> listOf("gemini", "local")
        LlmMode.AUTO -> (if (c.preferGemini) listOf("gemini", "ollama") else listOf("ollama", "gemini")) + "local"
    }
    private fun needsRoleRecovery(c: LlmConfig, results: List<Result>): Boolean =
        c.multiVoice && c.mode !in listOf(LlmMode.OFF, LlmMode.LOCAL) && results.any {
            !it.rolesReady || (c.mode != LlmMode.LOCAL && it.roleProvider != roleProviders(c).firstOrNull())
        }
    private fun routeRoles(ctx: Context, c: LlmConfig, prepared: List<Result>, preceding: String,
                           expectedEpoch: Long, ignoreCooldown: Boolean, cancelled: () -> Boolean,
                           recovering: Boolean = false,
                           resolved: (Int, Result) -> Unit = { _, _ -> }, roleRequest: RoleRequest? = null): List<Result> {
        val texts = prepared.map { it.text }
        val key = RoleKey(c, preceding, texts)
        val now = SystemClock.elapsedRealtime()
        val deadline = now + 18_000
        val cloudDeadline = now + 9_000
        val cached = synchronized(roleCache) { roleCache[key] }?.takeIf {
            it.routing.providers.all { p -> p == roleProviders(c).firstOrNull() } || (!recovering && now - it.time < 30_000)
        }
        // Local inference was already attempted while preparing these paragraphs.
        // Repeating the same failed Gemma output every ten seconds burns CPU/GPU
        // without new context. Recovery probes the clouds; the next paragraph
        // still gets its normal local fallback, and valid existing roles survive.
        val routing = cached?.routing ?: VoiceRoleRouting.resolve(texts, roleProviders(c), preceding,
            available = { provider ->
                expectedEpoch == epoch && !cancelled() && SystemClock.elapsedRealtime() < (if(provider=="local") deadline else cloudDeadline) &&
                    (ignoreCooldown || now >= (roleCooldown[provider] ?: 0L)) &&
                    when (provider) {
                        "local" -> LocalModelDownload.activeReady(ctx)
                        "gemini" -> connected(ctx) && c.geminiModel.isNotBlank() && c.geminiKey.isNotBlank()
                        else -> connected(ctx) && c.ollamaModel.isNotBlank()
                    }
            }, request = { provider, parts, before ->
                check(expectedEpoch == epoch && !cancelled())
                roleRequest?.invoke(provider, parts, before) ?: LlmProviders.voiceRoles(ctx, c, parts, before, provider,
                    minOf(8000L, (if(provider=="local") deadline else cloudDeadline) - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            }, failed = { provider, error ->
                val pause = if (Regex("API HTTP (401|403|429)").containsMatchIn(error.message.orEmpty())) 60_000 else 10_000
                roleCooldown[provider] = SystemClock.elapsedRealtime() + pause
                Log.w("MultiVoice", "Role request failed provider=$provider error=${error.javaClass.simpleName}; trying next provider")
            }, resolved = { index, plan, provider ->
                if (expectedEpoch == epoch && !cancelled()) resolved(index,
                    prepared[index].copy(voicePlan = plan, rolesReady = true, roleProvider = provider))
            }, existing = if (recovering) null else VoiceRoleRouting.Result(
                prepared.map { if(it.rolesReady) it.voicePlan else null },prepared.map { it.roleProvider }), cloudRecovery = recovering)
        if (expectedEpoch != epoch || cancelled()) return prepared
        if (cached == null && routing.plans.all { it != null }) synchronized(roleCache) {
            val cost = texts.sumOf { it.length } + preceding.length
            roleCache.remove(key)?.let { roleCacheChars -= cost }
            while (roleCache.isNotEmpty() && (roleCache.size >= 64 || roleCacheChars + cost > 128_000)) {
                val old = roleCache.keys.first()
                roleCacheChars -= old.texts.sumOf { it.length } + old.preceding.length
                roleCache.remove(old)
            }
            roleCache[key] = RoleCached(routing, now); roleCacheChars += cost
        }
        val results = prepared.mapIndexed { i, result ->
            val plan = routing.plans[i]
            // A failed retry must never erase an existing validated voice plan.
            if (plan == null) result else result.copy(voicePlan = plan, rolesReady = true, roleProvider = routing.providers[i])
        }
        Log.i("MultiVoice", "Prepared routing providers=${results.map { it.roleProvider }.distinct()} ready=${results.count { it.rolesReady }}/${results.size} cache=${cached != null} recovery=$recovering roles=${results.flatMap { it.voicePlan }.groupingBy { it.role }.eachCount()}")
        return results
    }
    private fun scheduleRoleRecovery(ctx: Context, c: LlmConfig, batch: List<Entry>, preceding: String,
                                     expectedEpoch: Long, attempt: Int = 0, roleRequest: RoleRequest? = null) {
        timer.schedule({ executor.execute {
            if (expectedEpoch != epoch) return@execute
            val pending = synchronized(lock) { batch.filter { entries[it.id] === it && !it.cancelled && it.textReady != null } }
            if (pending.isEmpty()) return@execute
            val before = preceding + batch.takeWhile { it !== pending.first() }.joinToString("\n") { it.textReady?.text ?: it.input }
            val results = pending.map { it.textReady!! }
            val recovered = if (!connected(ctx) && results.all { it.rolesReady }) results else
                routeRoles(ctx, c, results, before.takeLast(1800), expectedEpoch, false,
                    cancelled = { expectedEpoch != epoch || pending.all { it.cancelled } }, recovering = true, roleRequest = roleRequest)
            pending.zip(recovered).forEach { (entry, result) ->
                val accepted = synchronized(lock) {
                    if (expectedEpoch != epoch || entries[entry.id] !== entry || entry.cancelled) false
                    else { entry.textReady = result; true }
                }
                if (accepted && result.rolesReady) ReaderAudioAhead.refreshPrepared(ctx, entry.text, result)
            }
            if (needsRoleRecovery(c, recovered)) scheduleRoleRecovery(ctx, c, pending, before.takeLast(1800), expectedEpoch, attempt + 1, roleRequest)
            else Log.i("MultiVoice", "Cloud/local roles recovered in background fragments=${pending.size}; no cache clear required")
        } }, if (attempt == 0) 5 else 10, TimeUnit.SECONDS)
    }
    private fun processText(ctx: Context, c: LlmConfig, texts: List<String>, expectedEpoch: Long = epoch,
                        ignoreCooldown: Boolean = false, traceSynthetic: Boolean = false, cancelled: () -> Boolean = { false },
                        onPrepared: (Int, Result) -> Unit = { _, _ -> }, beforeRetry: (List<Result?>) -> Unit = {}): List<Result> {
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
        // The LLM sees the text as it will be read: units, times, percents, phones, ordinal suffixes, Roman numerals
        // and abbreviations are spelled out first (they used to stay digits/letters and reach the voice unstressed:
        // "пять км", "Пётр I" through the English engine). Dates stay for prepareNumbers, which marks their spans.
        val numericInputs = texts.map { com.brahmadeo.supertonic.tts.foreign.ForeignText.prepareNumbers(
            com.brahmadeo.supertonic.tts.utils.RussianBookNormalizer.normalize(it, expandNumbers = false, dates = false), russianNumbers) }
        val providerTexts = numericInputs.map { it.text }
        val providers = when (c.mode) {
            LlmMode.OFF -> emptyList()
            LlmMode.LOCAL -> listOf("local")
            LlmMode.OLLAMA -> listOf("ollama", "local")
            LlmMode.GEMINI -> listOf("gemini", "local")
            LlmMode.AUTO -> (if (c.preferGemini) listOf("gemini", "ollama") else listOf("ollama", "gemini")) + "local"
        }
        for (provider in providers) {
            if (results.any { it != null } && results.any { it == null }) beforeRetry(results.toList())
            if (expectedEpoch != epoch || cancelled()) break
            if (provider != "local" && !connected(ctx)) continue
            if (provider == "ollama" && c.ollamaModel.isBlank()) continue
            if (provider == "gemini" && (c.geminiKey.isBlank() || c.geminiModel.isBlank())) continue
            if (provider == "local" && !LocalModelDownload.activeReady(ctx)) continue
            // A cloud already accepted almost the whole batch: a 30-50 s Gemma retry of the last
            // few fragments would block the single worker and push later paragraphs to the
            // dictionary. Those few fragments use the offline fallback instead.
            val resolved = results.count { it != null }
            if (provider == "local" && resolved > 0 && results.size - resolved <= 3 && resolved * 5 >= results.size * 4) {
                Log.i("LlmPreparation", "Skipping local retry for ${results.size - resolved} fragment(s) after cloud success")
                continue
            }
            if (!ignoreCooldown && SystemClock.elapsedRealtime() < (cooldown[provider] ?: 0L)) {
                // The last provider pausing briefly must not flush the whole queue to the dictionary in seconds
                // (15 s of Gemma cooldown once turned ~7000 chars into dictionary text): wait for it instead.
                // Playback never waits on this: prepareResult() has its own timeout.
                val until = cooldown[provider] ?: 0L
                if (provider != providers.last() || until - SystemClock.elapsedRealtime() > 20_000) continue
                while (SystemClock.elapsedRealtime() < until && expectedEpoch == epoch && !cancelled()) Thread.sleep(200)
                if (expectedEpoch != epoch || cancelled()) break
            }
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
                    fun check(text: String, onReject: (String) -> Unit) = PreparedTextValidator.validate(source, text, c.punctuation, c.stress,
                        numericInputs[index].ranges, c.restoreYo, requireStress=provider!="local" && c.stress, onReject)
                    // One rewritten word or misplaced mark must not send a whole, otherwise correct
                    // paragraph to the offline dictionary: repair only those words, then re-validate.
                    val validated = check(proposed) { rejection = it } ?: PreparedTextRepair.repair(source, proposed)?.let { fix ->
                        check(fix.text) {}?.also { Log.i("LlmPreparation", "Repaired fragment=$index, chars=${source.length}, provider=$provider, reason=$rejection, replacedWords=${fix.replacedWords}") }
                    }
                    if (traceSynthetic) Log.i("SpeechCheck","SYNTHETIC PROPOSAL provider=$provider: $proposed")
                    if (validated != null) {
                        // Names: learn from the cloud models (the small local one stresses them less reliably),
                        // then give a known name the LLM left unmarked the stress this book already uses.
                        probeRaw?.put(source, validated)
                        if (c.stress && provider != "local") NameStress.learn(validated)
                        val learned = if (c.stress) NameStress.fill(validated) else validated
                        // Names (Семён, Пётр, Фёдор) from Wiktionary; ordinary words from Silero's safe ё table. Books
                        // typed without ё ("Семен", "черный") otherwise depended on the LLM noticing every one.
                        val withNames = if (c.stress || c.restoreYo) com.brahmadeo.supertonic.tts.utils.RussianNames.restore(ctx, learned) else learned
                        val named = if (c.restoreYo && provider != "local") com.brahmadeo.supertonic.tts.utils.YoRestore.apply(withNames, ambiguousLocalYo) {
                            com.brahmadeo.supertonic.tts.local.LocalRussianStress.yoForm(ctx, it) } else withNames
                        val completed=if(provider=="local" && (c.stress || c.restoreYo)) {
                            // Stress: the offline Silero Stress outscored Gemma, so it decides where both mark a word.
                            // One Silero pass serves both the arbitration and the fill of words Gemma left unmarked.
                            val offlineOpinion=offlineMarks(ctx,named,null)
                            val arbitrated=if(c.stress) StressCheck.preferOffline(named,offlineOpinion,source) else named
                            val safe=com.brahmadeo.supertonic.tts.utils.RussianYoPolicy.apply(arbitrated,offlineOpinion,c.restoreYo)
                            MissingSpeechMarks.merge(arbitrated,safe,c.stress,c.restoreYo,ambiguousLocalYo)
                        } else if (c.stress) {
                            // A word the cloud LLM left unmarked used to reach the TTS model as a guess (eSpeak in
                            // Kokoro). The offline Silero Stress reads the whole sentence (context BERT for homographs,
                            // accentor for unknown words) and fills only those words; LLM marks and ё stay as they are.
                            MissingSpeechMarks.merge(named,offlineMarks(ctx,named,batchOpinions?.getOrNull(requestIndex)),stress=true,yo=false)
                        } else named
                        val supplemented=completed!=named
                        if (supplemented && provider!="local") Log.i("LlmPreparation","Cloud stress completed fragment=$index provider=$provider llmMarks=${named.count { it=='́' }} offlineAdded=${completed.count { it=='́' }-named.count { it=='́' }}")
                        if(provider=="local") com.brahmadeo.supertonic.tts.utils.DiagLog.i("LlmPreparation","Local supplement chars=${validated.length} llmEdited=${validated!=source} llmStress=${validated.count { it=='\u0301' }} llmYoAdded=${validated.count { it in "ёЁ" }-source.count { it in "ёЁ" }} changed=$supplemented; explicit LLM stress/yo retained")
                        val result = Result(completed, if(supplemented && provider=="local") "local+offline" else provider, SystemClock.elapsedRealtime()-started, false)
                        results[index] = result; accepted++
                        onPrepared(index, result)
                        if (provider == "local") preparedCache.put(c,texts,results.map { it?.text })
                    } else Log.w("LlmPreparation", "Rejected fragment=$index, chars=${source.length}, provider=$provider, reason=$rejection")
                }
                if (provider == "local") {
                    LlmProviders.local(ctx,c,requestTexts,deadlineMs=if(c.multiVoice) 12000 else 45000,onOutput=::accept)
                } else {
                    val outputs=LlmProviders.cloud(c,requestTexts,provider=="gemini",if (c.stress) (NameStress.hint(requestTexts) +
                        com.brahmadeo.supertonic.tts.utils.RussianNames.hint(ctx, requestTexts)).distinctBy { it.replace("\u0301", "").lowercase() }.take(60) else emptyList())
                    val (checked, opinions) = if (c.stress) crossCheck(ctx,c,outputs,provider=="gemini") else outputs to null
                    batchOpinions = opinions
                    try { checked.forEachIndexed { index,text -> accept(index,text) } } finally { batchOpinions = null }
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
                // Network blips and timeouts recover quickly; only auth/quota errors need a long pause.
                // A slow local fragment is not an outage: Gemma stays available for the next batch.
                if (!(provider == "local" && failure.orEmpty().startsWith("LLM локальная: превышен лимит")))
                    cooldown[provider] = SystemClock.elapsedRealtime() + if (Regex("API HTTP (401|403|429)").containsMatchIn(failure.orEmpty())) 60_000 else 15_000
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
