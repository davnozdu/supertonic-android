package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.util.Log
import com.brahmadeo.supertonic.tts.R
import com.brahmadeo.supertonic.tts.tera.TeraStressDictionary
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
    const val TERA_MODEL = "teratts_v2"
    const val SILERO_MODEL = "silero_v5_5_ru"
    const val SILERO_CIS_MODEL = "silero_cis_ru"
    private const val TERA_REVISION = "f05ea799094571a3553904a555df3834fb0b963b"
    private const val TERA_BASE_URL = "https://huggingface.co/TeraSpace/TeraTTSv2/resolve/$TERA_REVISION"
    private const val TERA_DICTIONARY_URL =
        "https://github.com/davnozdu/supertonic-dictionaries/releases/download/russian-v1.1"
    val TERA_VOICES = listOf("ru_f1", "ru_f2", "ru_m1", "ru_m5")

    fun isTera(context: Context): Boolean = getModelType(context) == TERA_MODEL
    fun isSilero(context: Context): Boolean = getModelType(context) in setOf(SILERO_MODEL, SILERO_CIS_MODEL)
    fun isRussianModel(context: Context): Boolean = isTera(context) || isSilero(context)

    fun voiceFile(context: Context, selected: String): File {
        val base = File(context.filesDir, MODEL_VERSION)
        if (isSilero(context)) {
            val name = File(selected).name.removeSuffix(".json")
                .takeIf { it in com.brahmadeo.supertonic.tts.silero.SileroDownload.voices(context) } ?: com.brahmadeo.supertonic.tts.silero.SileroDownload.defaultVoice(context)
            return File(com.brahmadeo.supertonic.tts.silero.SileroDownload.root(context), "$name.json")
        }
        if (!isTera(context)) return File(base, "voice_styles/${File(selected).name}")
        val voice = File(selected).name.removeSuffix(".json")
            .takeIf { it in TERA_VOICES } ?: "ru_f1"
        return File(base, "tera/styles/$voice/style_ttl.npy")
    }

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
    private data class AssetFile(val remoteName: String, val localPath: String, val baseUrl: String = ASSETS_BASE_URL)

    private fun voiceFilesFrom(prefix: String): List<AssetFile> =
        VOICE_FILES.map { local ->
            // local = "voice_styles/M1.json" -> remote = "<prefix>_voice_M1.json"
            val basename = local.removePrefix("voice_styles/").removeSuffix(".json")
            AssetFile("${prefix}_voice_${basename}.json", local)
        }

    private fun getFilesForModel(modelType: String): List<AssetFile> =
        when (modelType) {
            TERA_MODEL -> {
                val paths = listOf(
                    "models/duration_predictor.onnx", "models/text_encoder.onnx",
                    "models/sampler_distilled_cfg3_8step.onnx", "models/vocoder.onnx",
                    "unicode_indexer.json",
                    "ruaccent/dictionary/yo_words.json.gz"
                ) + TERA_VOICES.flatMap { voice ->
                    listOf("styles/$voice/style_dp.npy", "styles/$voice/style_ttl.npy")
                }
                paths.map { AssetFile(it, "tera/$it", TERA_BASE_URL) } +
                    AssetFile("tera_accents.sacc", "tera/accents.sacc", TERA_DICTIONARY_URL)
            }
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
        if (isRussianModel(context) && !com.brahmadeo.supertonic.tts.local.LocalRussianAssets.ready(context)) return false
        if (isSilero(context)) return com.brahmadeo.supertonic.tts.silero.SileroDownload.supported() &&
            com.brahmadeo.supertonic.tts.silero.SileroDownload.ready(context)
        val baseDir = File(context.filesDir, MODEL_VERSION)
        if (!baseDir.exists()) return false
        
        val prefs = context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        val lastModelType = prefs.getString("last_downloaded_model", null)
        val currentModelType = getModelType(context)
        
        if (lastModelType != currentModelType) return false
        
        val files = getFilesForModel(currentModelType)
        return files.all { File(baseDir, it.localPath).exists() } &&
            (currentModelType != TERA_MODEL ||
                BinaryAccentDictionary.looksLikeSacc(TeraStressDictionary.databaseFile(File(baseDir, "tera"))))
    }

    suspend fun download(context: Context, onProgress: (String, Float) -> Unit) {
        if (isSilero(context)) {
            com.brahmadeo.supertonic.tts.silero.SileroDownload.download(context) { status, value -> onProgress(status, value * .7f) }
            com.brahmadeo.supertonic.tts.local.LocalRussianAssets.download(context) { status, value -> onProgress(status, .7f + value * .3f) }
            return
        }
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
                                val url = "${asset.baseUrl}/${asset.remoteName}"
                                val partFile = File(targetFile.absolutePath + ".part")
                                try {
                                    Log.d(TAG, "Downloading $url -> ${targetFile.absolutePath}")
                                    partFile.delete()
                                    URL(url).openStream().use { input ->
                                        FileOutputStream(partFile).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    check(partFile.length() > 0 && partFile.renameTo(targetFile)) {
                                        "Could not finish download of ${asset.remoteName}"
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to download ${asset.remoteName}", e)
                                    partFile.delete()
                                    throw e
                                }
                            }
                        }
                        val n = finished.incrementAndGet()
                        val downloadShare = if (modelType == TERA_MODEL) 0.9f else 1f
                        onProgress(context.getString(R.string.download_file_status, asset.localPath), n.toFloat() / files.size * downloadShare)
                    }
                }.awaitAll()
            }
            if (modelType == TERA_MODEL) {
                val teraRoot = File(baseDir, "tera")
                if (!BinaryAccentDictionary.looksLikeSacc(TeraStressDictionary.databaseFile(teraRoot))) {
                    // Existing interrupted installs may still have the old JSON.
                    // New installs download the ready-to-use binary from GitHub.
                    onProgress(context.getString(R.string.download_index_status), 0.95f)
                    TeraStressDictionary.prepare(teraRoot)
                }
            }
            
            if (modelType == TERA_MODEL) com.brahmadeo.supertonic.tts.local.LocalRussianAssets.download(context, onProgress)
            prefs.edit().putString("last_downloaded_model", modelType).apply()
            onProgress(context.getString(R.string.download_ready_status), 1.0f)
        }
    }

    fun delete(context: Context) {
        if (isSilero(context)) {
            com.brahmadeo.supertonic.tts.silero.SileroDownload.root(context).deleteRecursively()
            return
        }
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
