package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

object AssetManager {
    private const val TAG = "AssetManager"
    const val MODEL_VERSION = "v3"

    // Mirror of Supertone/supertonic-3 and Reza2kn/supertonic-3-litert assets
    // hosted as a GitHub Release on this app's own repo. Single source of truth
    // for first-launch downloads — Hugging Face is no longer reached.
    private const val ASSETS_BASE_URL =
        "https://github.com/davnozdu/supertonic-android/releases/download/assets-v1"

    private val VOICE_FILES = listOf(
        "voice_styles/M1.json", "voice_styles/M2.json", "voice_styles/M3.json", "voice_styles/M4.json", "voice_styles/M5.json",
        "voice_styles/F1.json", "voice_styles/F2.json", "voice_styles/F3.json", "voice_styles/F4.json", "voice_styles/F5.json"
    )

    const val DEFAULT_MODEL = "android_optimized_int8"

    fun getModelType(context: Context): String {
        return context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
            .getString("selected_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    fun setModelType(context: Context, type: String) {
        context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
            .edit().putString("selected_model", type).apply()
    }

    /**
     * @param remoteName flat filename on the GitHub Release (e.g. "supertone_vocoder.onnx")
     * @param localPath path under filesDir/v3/ — keeps the same layout the engines
     *                   expect (onnx/<file>, voice_styles/<file>)
     */
    private data class AssetFile(val remoteName: String, val localPath: String)

    private fun voiceFilesFrom(prefix: String): List<AssetFile> =
        VOICE_FILES.map { local ->
            // local = "voice_styles/M1.json" -> remote = "<prefix>_voice_M1.json"
            val basename = local.removePrefix("voice_styles/").removeSuffix(".json")
            AssetFile("${prefix}_voice_${basename}.json", local)
        }

    private fun getFilesForModel(modelType: String): List<AssetFile> =
        when (modelType) {
            "android_optimized_int8" -> {
                // Hybrid INT4 .tflite + INT8 VE .onnx + FP32 vocoder .onnx.
                listOf(
                    AssetFile("reza_int4_duration_predictor.tflite", "onnx/duration_predictor.tflite"),
                    AssetFile("reza_int4_text_encoder.tflite",       "onnx/text_encoder.tflite"),
                    AssetFile("reza_vector_estimator_int8.onnx",     "onnx/vector_estimator.onnx"),
                    AssetFile("supertone_vocoder.onnx",              "onnx/vocoder.onnx"),
                    AssetFile("supertone_tts.json",                  "onnx/tts.json"),
                    AssetFile("supertone_unicode_indexer.json",      "onnx/unicode_indexer.json"),
                ) + voiceFilesFrom("reza")
            }
            "android_optimized_fp32" -> {
                listOf(
                    AssetFile("supertone_duration_predictor.onnx",   "onnx/duration_predictor.onnx"),
                    AssetFile("supertone_text_encoder.onnx",         "onnx/text_encoder.onnx"),
                    AssetFile("reza_vector_estimator.onnx",          "onnx/vector_estimator.onnx"),
                    AssetFile("supertone_vocoder.onnx",              "onnx/vocoder.onnx"),
                    AssetFile("supertone_tts.json",                  "onnx/tts.json"),
                    AssetFile("supertone_unicode_indexer.json",      "onnx/unicode_indexer.json"),
                ) + voiceFilesFrom("reza")
            }
            "android_optimized_fp16" -> {
                // Kyumdroid/supertonic-3-quant: fp16 weights with fp32 I/O
                // (keep_io_types=True), so it runs on the same Rust ORT path
                // as the fp32 presets at roughly half the download size.
                listOf(
                    AssetFile("kyum_fp16_duration_predictor.onnx",   "onnx/duration_predictor.onnx"),
                    AssetFile("kyum_fp16_text_encoder.onnx",         "onnx/text_encoder.onnx"),
                    AssetFile("kyum_fp16_vector_estimator.onnx",     "onnx/vector_estimator.onnx"),
                    AssetFile("kyum_fp16_vocoder.onnx",              "onnx/vocoder.onnx"),
                    AssetFile("supertone_tts.json",                  "onnx/tts.json"),
                    AssetFile("supertone_unicode_indexer.json",      "onnx/unicode_indexer.json"),
                ) + voiceFilesFrom("supertone")
            }
            else -> { // standard
                listOf(
                    AssetFile("supertone_duration_predictor.onnx",   "onnx/duration_predictor.onnx"),
                    AssetFile("supertone_text_encoder.onnx",         "onnx/text_encoder.onnx"),
                    AssetFile("supertone_vector_estimator.onnx",     "onnx/vector_estimator.onnx"),
                    AssetFile("supertone_vocoder.onnx",              "onnx/vocoder.onnx"),
                    AssetFile("supertone_tts.json",                  "onnx/tts.json"),
                    AssetFile("supertone_unicode_indexer.json",      "onnx/unicode_indexer.json"),
                ) + voiceFilesFrom("supertone")
            }
        }

    fun isReady(context: Context): Boolean {
        val baseDir = File(context.filesDir, MODEL_VERSION)
        if (!baseDir.exists()) return false
        
        val prefs = context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        val lastModelType = prefs.getString("last_downloaded_model", null)
        val currentModelType = getModelType(context)
        
        if (lastModelType != currentModelType) return false
        
        val files = getFilesForModel(currentModelType)
        return files.all { File(baseDir, it.localPath).exists() }
    }

    suspend fun download(context: Context, onProgress: (String, Float) -> Unit) {
        val modelType = getModelType(context)
        val files = getFilesForModel(modelType)

        withContext(Dispatchers.IO) {
            val baseDir = File(context.filesDir, MODEL_VERSION)
            if (!baseDir.exists()) baseDir.mkdirs()

            // If we are changing models, clear the directory first to avoid mixing
            val prefs = context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
            val lastModelType = prefs.getString("last_downloaded_model", null)
            if (lastModelType != null && lastModelType != modelType) {
                baseDir.deleteRecursively()
                baseDir.mkdirs()
            }

            // Up to 4 files in flight simultaneously — big files like
            // vector_estimator.onnx (~64 MB) and vocoder.onnx (~97 MB) used
            // to download serially after every small voice JSON. Parallel
            // saturates the GitHub Releases CDN and the device's bandwidth,
            // typically 2-3x faster end-to-end on a healthy connection.
            val sema = Semaphore(4)
            val finished = AtomicInteger(0)
            coroutineScope {
                files.map { asset ->
                    async {
                        sema.withPermit {
                            val targetFile = File(baseDir, asset.localPath)
                            if (!targetFile.exists()) {
                                targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                                val url = "$ASSETS_BASE_URL/${asset.remoteName}"
                                try {
                                    Log.d(TAG, "Downloading $url -> ${targetFile.absolutePath}")
                                    URL(url).openStream().use { input ->
                                        FileOutputStream(targetFile).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to download ${asset.remoteName}", e)
                                    targetFile.delete()
                                    throw e
                                }
                            }
                        }
                        val n = finished.incrementAndGet()
                        onProgress("Downloading ${asset.localPath}...", n.toFloat() / files.size)
                    }
                }.awaitAll()
            }
            
            prefs.edit().putString("last_downloaded_model", modelType).apply()
            onProgress("Ready", 1.0f)
        }
    }

    fun delete(context: Context) {
        val baseDir = File(context.filesDir, MODEL_VERSION)
        if (baseDir.exists()) {
            baseDir.deleteRecursively()
        }
        context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
            .edit().remove("last_downloaded_model").apply()
    }

    fun cleanupOldVersions(context: Context) {
        listOf("v1", "v2").forEach { old ->
            val dir = File(context.filesDir, old)
            if (dir.exists()) {
                Log.i(TAG, "Cleaning up legacy model dir: $old")
                dir.deleteRecursively()
            }
        }
    }
}
