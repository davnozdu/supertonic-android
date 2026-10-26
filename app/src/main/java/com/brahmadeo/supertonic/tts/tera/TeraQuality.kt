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
    /** The distilled sampler with MatMul in int8 inside its Loop body: 65 MB, ~3x less CPU per phrase, slightly different
     * sound. Quantized exactly like the TeraTTS fork (github.com/9208499-ship-it/teratts-android, tools/quantize_tera2.py;
     * output bit-identical to theirs), but the Loop is kept instead of unrolled: 2.5k nodes instead of 20k, so the session
     * opens in ~0.3 s instead of ~7–12 s. Built by tools/tera_int8/loop_int8.py. */
    const val INT8="sampler_loop_int8"
    private const val INT8_SIZE=65447786L
    private const val INT8_SHA="50101128535530a307ae414ee21b4b8c6e76d51b2da1a36cd683d38592a1980f"
    /** The unrolled build shipped in 5.0.5-beta22/23: slow to open, removed once the new one is fetched. */
    private const val OLD_INT8="sampler_distilled_int8"
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
            File(root,"$OLD_INT8.onnx").delete()
        }
    }
}
