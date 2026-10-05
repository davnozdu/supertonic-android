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
    fun failed(ctx: Context) = prefs(ctx).getLong(FAILED, -1L) == version(ctx)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean(KEY, false) && supported(ctx) && !failed(ctx)
    fun markFailed(ctx: Context, reason: String) {
        Log.w(TAG, "NPU disabled for this version: $reason")
        prefs(ctx).edit().putLong(FAILED, version(ctx)).apply()
    }
    fun clearFailure(ctx: Context) { prefs(ctx).edit().remove(FAILED).apply() }

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
                performance: String = "high_performance", allowCpuFallback: Boolean = false, logInfo: Boolean = false): OrtSession {
        val cacheDir = File(ctx.filesDir, "npu-cache").apply { mkdirs() }
        val cached = File(cacheDir, "$cacheName-v${version(ctx)}_ctx.onnx")
        cacheDir.listFiles()?.filter { it.name.startsWith("$cacheName-") && it != cached }?.forEach { it.delete() }
        OrtSession.SessionOptions().use { options ->
            dims.forEach { (name, value) -> options.setSymbolicDimensionValue(name, value) }
            options.setIntraOpNumThreads(1)
            if (!allowCpuFallback) options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
            if (logInfo) options.setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO)
            options.addQnn(options(ctx, performance))
            return if (cached.isFile) env.createSession(cached.path, options) else {
                options.addConfigEntry("ep.context_enable", "1")
                options.addConfigEntry("ep.context_embed_mode", "1")
                options.addConfigEntry("ep.context_file_path", cached.path)
                env.createSession(model.path, options)
            }
        }
    }
}
