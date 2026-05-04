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
    private const val BASE_URL = "https://huggingface.co/Supertone/supertonic-2/resolve/main"
    
    private val FILES = listOf(
        "onnx/duration_predictor.onnx",
        "onnx/text_encoder.onnx",
        "onnx/vector_estimator.onnx",
        "onnx/vocoder.onnx",
        "onnx/tts.json",
        "onnx/unicode_indexer.json",
        // V2 voices (same names, different files)
        "voice_styles/M1.json", "voice_styles/M2.json", "voice_styles/M3.json", "voice_styles/M4.json", "voice_styles/M5.json",
        "voice_styles/F1.json", "voice_styles/F2.json", "voice_styles/F3.json", "voice_styles/F4.json", "voice_styles/F5.json"
    )

    fun isReady(context: Context): Boolean {
        val baseDir = File(context.filesDir, "v2")
        if (!baseDir.exists()) return false
        return FILES.all { File(baseDir, it).exists() }
    }

    suspend fun download(context: Context, onProgress: (String, Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val baseDir = File(context.filesDir, "v2")
            if (!baseDir.exists()) baseDir.mkdirs()

            FILES.forEachIndexed { index, relativePath ->
                val targetFile = File(baseDir, relativePath)
                if (targetFile.exists()) {
                    onProgress("Checking v2/$relativePath...", (index.toFloat() / FILES.size))
                    return@forEachIndexed
                }

                targetFile.parentFile?.let {
                    if (!it.exists()) it.mkdirs()
                }

                val url = "$BASE_URL/$relativePath"
                try {
                    onProgress("Downloading v2/$relativePath...", (index.toFloat() / FILES.size))
                    Log.d(TAG, "Downloading $url to ${targetFile.absolutePath}")
                    
                    URL(url).openStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download $relativePath", e)
                    targetFile.delete()
                    throw e
                }
            }
            onProgress("Ready", 1.0f)
        }
    }

    fun delete(context: Context) {
        val baseDir = File(context.filesDir, "v2")
        if (baseDir.exists()) {
            baseDir.deleteRecursively()
        }
    }
}
