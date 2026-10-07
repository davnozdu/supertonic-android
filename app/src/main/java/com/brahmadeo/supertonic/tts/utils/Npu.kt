package com.brahmadeo.supertonic.tts.utils

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/** Optional Qualcomm Hexagon NPU (QNN HTP) for fixed-shape ONNX graphs. Off by default; any
 * failure falls back to the CPU and is remembered per app version so reading never stalls. */
object Npu {
    const val KEY = "npu_enabled"
    private const val FAILED = "npu_failed_version"
    private const val TAG = "Npu"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
    private fun version(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L)
    private fun backend(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libQnnHtp.so")

    fun supported(ctx: Context) = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && backend(ctx).isFile
    /** One HTP for QNN (Kokoro, Tera vocoder) and ggml-hexagon (Gemma): concurrent graphs failed with QNN 1002 and
     * slowed each other 4×. Every NPU call takes this fair lock; Gemma takes it per llama_decode (from JNI). */
    private val htp = java.util.concurrent.locks.ReentrantLock(true)
    inline fun <T> exclusive(block: () -> T): T { lockHtp(); try { return block() } finally { unlockHtp() } }
    /** Backoff after consecutive NPU run errors: 30 s, 1, 2, 4 min, then 5 min; the NPU is never given up. */
    fun retryDelayMs(failures: Int): Long = minOf(30_000L shl (failures - 1).coerceIn(0, 4), 300_000L)
    @JvmStatic fun lockHtp() = htp.lock()
    @JvmStatic fun unlockHtp() = htp.unlock()

    /** Scopes keep a Kokoro failure from switching off the Tera vocoder and vice versa. */
    const val KOKORO = "_kokoro"
    const val TERA_SAMPLER = "_tera_sampler"
    fun failed(ctx: Context, scope: String = "") = prefs(ctx).getLong(FAILED + scope, -1L) == version(ctx)
    fun enabled(ctx: Context, scope: String = "") = prefs(ctx).getBoolean(KEY, false) && supported(ctx) && !failed(ctx, scope)
    fun markFailed(ctx: Context, reason: String, scope: String = "") {
        Log.w(TAG, "NPU$scope disabled for this version: $reason")
        prefs(ctx).edit().putLong(FAILED + scope, version(ctx)).apply()
    }
    fun clearFailure(ctx: Context) { prefs(ctx).edit().remove(FAILED).remove(FAILED + KOKORO).remove(FAILED + TERA_SAMPLER).apply() }

    fun options(ctx: Context, performance: String = "high_performance"): Map<String, String> {
        // The DSP loads the HTP skel through FastRPC from the app's extracted native libraries.
        val dir = ctx.applicationInfo.nativeLibraryDir
        runCatching { android.system.Os.setenv("ADSP_LIBRARY_PATH", "$dir;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", true) }
        return mapOf("backend_path" to backend(ctx).path, "htp_performance_mode" to performance,
            "enable_htp_fp16_precision" to "1", "htp_graph_finalization_optimization_mode" to "3")
    }

    /** Session on the NPU with fixed symbolic dimensions. The compiled graph is cached once in
     * app storage (one flash write); later loads skip the multi-second HTP compilation. */
    fun session(ctx: Context, env: OrtEnvironment, model: File, dims: Map<String, Long>, cacheName: String,
                performance: String = "high_performance", allowCpuFallback: Boolean = false, logInfo: Boolean = false,
                extra: Map<String, String> = emptyMap(), verbose: Boolean = false): OrtSession {
        val cacheDir = File(ctx.filesDir, "npu-cache").apply { mkdirs() }
        val cached = File(cacheDir, "$cacheName-v${version(ctx)}_ctx.onnx")
        cacheDir.listFiles()?.filter { it.name.startsWith("$cacheName-") && it != cached }?.forEach { it.delete() }
        OrtSession.SessionOptions().use { options ->
            dims.forEach { (name, value) -> options.setSymbolicDimensionValue(name, value) }
            options.setIntraOpNumThreads(1)
            if (!allowCpuFallback) options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
            if (logInfo || verbose) options.setSessionLogLevel(if (verbose) ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE else ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO)
            options.addQnn(options(ctx, performance) + extra)
            return if (cached.isFile) env.createSession(cached.path, options) else {
                options.addConfigEntry("ep.context_enable", "1")
                options.addConfigEntry("ep.context_embed_mode", "1")
                options.addConfigEntry("ep.context_file_path", cached.path)
                env.createSession(model.path, options)
            }
        }
    }

    /** Diagnostics only: "key=value;key=value" from a pref to try QNN/session options without a rebuild. */
    private fun debug(ctx: Context, key: String): Map<String, String> = prefs(ctx).getString(key, null).orEmpty()
        .split(';').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 && p[0].isNotBlank() }?.let { p -> p[0].trim() to p[1].trim() } }.toMap()

    /** True when a shared context for [cacheName] is compiled for this app version (loading takes seconds). */
    fun sharedCacheReady(ctx: Context, cacheName: String) = File(File(ctx.filesDir, "npu-cache"), "$cacheName-v${version(ctx)}.done").isFile

    /** Many small graphs compiled into ONE QNN context (ORT ep.share_ep_contexts): one HTP context, one
     * context binary and shared HTP memory instead of a full context per graph. The binary is written when
     * the last graph compiles; a ".done" marker guards against a half-written cache. */
    fun sharedSessions(ctx: Context, env: OrtEnvironment, models: List<Pair<File, Map<String, Long>>>, cacheName: String,
                       performance: String = "high_performance"): List<OrtSession> {
        val cacheDir = File(ctx.filesDir, "npu-cache").apply { mkdirs() }
        val tag = "$cacheName-v${version(ctx)}"
        cacheDir.listFiles()?.filter { it.name.startsWith("$cacheName-") && !it.name.startsWith("$tag-") && !it.name.startsWith("$tag.") }?.forEach { it.delete() }
        fun ctxFile(i: Int) = File(cacheDir, "$tag-${i}_ctx.onnx")
        val bin = File(cacheDir, "$tag-0_ctx_qnn.bin")
        val done = File(cacheDir, "$tag.done")
        val cached = done.isFile && bin.isFile && models.indices.all { ctxFile(it).isFile }
        fun clean() = cacheDir.listFiles()?.filter { it.name.startsWith("$tag-") || it.name.startsWith("$tag.") }?.forEach { it.delete() }
        if (!cached) clean()
        val sessions = mutableListOf<OrtSession>()
        try {
            models.forEachIndexed { i, (model, dims) ->
                OrtSession.SessionOptions().use { o ->
                    dims.forEach { (name, value) -> o.setSymbolicDimensionValue(name, value) }
                    o.setIntraOpNumThreads(1)
                    o.addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    o.addConfigEntry("ep.share_ep_contexts", "1")
                    if (!cached) {
                        o.addConfigEntry("ep.context_enable", "1")
                        o.addConfigEntry("ep.context_embed_mode", "0")
                        o.addConfigEntry("ep.context_file_path", ctxFile(i).path)
                        if (i == models.lastIndex) o.addConfigEntry("ep.stop_share_ep_contexts", "1")
                    }
                    debug(ctx, "npu_debug_cfg").forEach { (k, v) -> o.addConfigEntry(k, v) }
                    // Shared-memory I/O allocator: ~330 MB less dmabuf for the Kokoro kit, same speed (measured).
                    o.addQnn(options(ctx, performance) + mapOf("enable_htp_shared_memory_allocator" to "1") + debug(ctx, "npu_debug_qnn"))
                    sessions += env.createSession(if (cached) ctxFile(i).path else model.path, o)
                }
            }
            if (!cached) { check(bin.isFile) { "shared NPU context binary missing" }; done.writeText(models.size.toString()) }
            return sessions
        } catch (t: Throwable) {
            sessions.forEach { runCatching { it.close() } }
            clean()
            throw t
        }
    }
}

