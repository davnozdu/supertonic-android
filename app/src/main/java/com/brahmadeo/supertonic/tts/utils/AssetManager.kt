package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object AssetManager {
    private const val TAG = "AssetManager"
    const val MODEL_VERSION = "v3"

    private const val STANDARD_BASE_URL = "https://huggingface.co/Supertone/supertonic-3/resolve/main"
    private const val ANDROID_BASE_URL = "https://huggingface.co/Reza2kn/supertonic-3-litert/resolve/main"

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

    private data class AssetFile(val remotePath: String, val localPath: String, val baseUrl: String)

    private fun getFilesForModel(modelType: String): List<AssetFile> {
        val standardBase = STANDARD_BASE_URL
        val androidBase = ANDROID_BASE_URL

        return when (modelType) {
            "android_optimized_int8" -> {
                // Hybrid INT4 .tflite + INT8 VE .onnx + FP32 vocoder .onnx.
                // INT4 vocoder.tflite is intentionally skipped — it adds an
                // audible 7-11 kHz metallic ringing the FP32 vocoder doesn't.
                listOf(
                    AssetFile("int4/duration_predictor.tflite", "onnx/duration_predictor.tflite", androidBase),
                    AssetFile("int4/text_encoder.tflite", "onnx/text_encoder.tflite", androidBase),
                    AssetFile("vector_estimator_int8.onnx", "onnx/vector_estimator.onnx", androidBase),
                    AssetFile("onnx/vocoder.onnx", "onnx/vocoder.onnx", standardBase),
                    AssetFile("onnx/tts.json", "onnx/tts.json", standardBase),
                    AssetFile("onnx/unicode_indexer.json", "onnx/unicode_indexer.json", standardBase)
                ) + VOICE_FILES.map { AssetFile(it, it, androidBase) }
            }
            "android_optimized_fp32" -> {
                listOf(
                    AssetFile("onnx/duration_predictor.onnx", "onnx/duration_predictor.onnx", standardBase),
                    AssetFile("onnx/text_encoder.onnx", "onnx/text_encoder.onnx", standardBase),
                    AssetFile("vector_estimator.onnx", "onnx/vector_estimator.onnx", androidBase),
                    AssetFile("onnx/vocoder.onnx", "onnx/vocoder.onnx", standardBase),
                    AssetFile("onnx/tts.json", "onnx/tts.json", standardBase),
                    AssetFile("onnx/unicode_indexer.json", "onnx/unicode_indexer.json", standardBase)
                ) + VOICE_FILES.map { AssetFile(it, it, androidBase) }
            }
            else -> { // standard
                listOf(
                    AssetFile("onnx/duration_predictor.onnx", "onnx/duration_predictor.onnx", standardBase),
                    AssetFile("onnx/text_encoder.onnx", "onnx/text_encoder.onnx", standardBase),
                    AssetFile("onnx/vector_estimator.onnx", "onnx/vector_estimator.onnx", standardBase),
                    AssetFile("onnx/vocoder.onnx", "onnx/vocoder.onnx", standardBase),
                    AssetFile("onnx/tts.json", "onnx/tts.json", standardBase),
                    AssetFile("onnx/unicode_indexer.json", "onnx/unicode_indexer.json", standardBase)
                ) + VOICE_FILES.map { AssetFile(it, it, standardBase) }
            }
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

            files.forEachIndexed { index, asset ->
                val targetFile = File(baseDir, asset.localPath)
                
                if (targetFile.exists()) {
                    onProgress("Checking ${asset.localPath}...", (index.toFloat() / files.size))
                    return@forEachIndexed
                }

                targetFile.parentFile?.let {
                    if (!it.exists()) it.mkdirs()
                }

                val url = "${asset.baseUrl}/${asset.remotePath}"
                try {
                    onProgress("Downloading ${asset.localPath}...", (index.toFloat() / files.size))
                    Log.d(TAG, "Downloading $url to ${targetFile.absolutePath}")

                    URL(url).openStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download ${asset.remotePath}", e)
                    targetFile.delete()
                    throw e
                }
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
