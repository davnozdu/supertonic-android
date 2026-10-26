package com.brahmadeo.supertonic.tts.tera

import android.content.Context
import com.brahmadeo.supertonic.tts.utils.AssetManager
import com.brahmadeo.supertonic.tts.utils.ModelDownloadForeground
import com.brahmadeo.supertonic.tts.utils.ResumableModelFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Optional sampler; never part of the base model's required download. */
object TeraQuality {
    const val FAST="sampler_distilled_cfg3_8step"
    const val TEACHER="sampler_teacher_8step"
    /** The distilled sampler with its Loop unrolled and MatMul in int8: 72 MB, ~4x less CPU, slightly different sound.
     * Built by tools/quantize_tera2.py of the TeraTTS fork (github.com/9208499-ship-it/teratts-android); our rebuild
     * from the original is byte-identical to theirs. */
    const val INT8="sampler_distilled_int8"
    private const val INT8_SIZE=71835413L
    private const val INT8_SHA="139209e76b0e0a8aff0e7f2eda0a9bf65f65265faafeca93cd2d5596e6e477db"
    private const val SIZE=256528347L
    private const val SHA="b38b6334cdc5fa228f260710a41082d52a581b29c1a474f87e2a0f70887258fb"
    private fun root(context: Context)=File(context.filesDir,"${AssetManager.MODEL_VERSION}/tera/models")
    fun ready(context: Context): Boolean {
        val root=root(context)
        return File(root,"$TEACHER.onnx").length()==SIZE &&
            File(root,"teacher.verified").let { it.isFile && runCatching { it.readText()==SHA }.getOrDefault(false) }
    }
    fun int8Ready(context: Context): Boolean =
        File(root(context),"$INT8.onnx").length()==INT8_SIZE &&
            File(root(context),"int8.verified").let { it.isFile && runCatching { it.readText()==INT8_SHA }.getOrDefault(false) }
    fun selected(context: Context): String {
        val prefs=context.getSharedPreferences("SupertonicPrefs",0)
        return when {
            prefs.getBoolean("tera_teacher",false) && ready(context) -> TEACHER
            prefs.getBoolean("tera_int8",false) && int8Ready(context) -> INT8
            else -> FAST
        }
    }
    // Network and file work off the caller's thread: the model package choice calls this from the main thread.
    suspend fun download(context: Context, progress: (Int)->Unit) = ModelDownloadForeground.run(context) {
        withContext(Dispatchers.IO) {
            val root=root(context).apply { mkdirs() }
            ResumableModelFile.fetch("https://github.com/davnozdu/supertonic-android/releases/download/russian-resources-v1/$TEACHER.onnx",
                File(root,"$TEACHER.onnx"),SIZE,SHA) { done,total -> progress((done*100/total).toInt()) }
            File(root,"teacher.verified").writeText(SHA)
        }
    }
    suspend fun downloadInt8(context: Context, progress: (Int)->Unit) = ModelDownloadForeground.run(context) {
        withContext(Dispatchers.IO) {
            val root=root(context).apply { mkdirs() }
            ResumableModelFile.fetch("https://github.com/davnozdu/supertonic-android/releases/download/russian-resources-v1/$INT8.onnx",
                File(root,"$INT8.onnx"),INT8_SIZE,INT8_SHA) { done,total -> progress((done*100/total).toInt()) }
            File(root,"int8.verified").writeText(INT8_SHA)
        }
    }
}
