package com.brahmadeo.supertonic.tts.tera

import android.content.Context
import com.brahmadeo.supertonic.tts.utils.AssetManager
import com.brahmadeo.supertonic.tts.utils.ModelDownloadForeground
import com.brahmadeo.supertonic.tts.utils.ResumableModelFile
import java.io.File

/** Optional sampler; never part of the base model's required download. */
object TeraQuality {
    const val FAST="sampler_distilled_cfg3_8step"
    const val TEACHER="sampler_teacher_8step"
    private const val SIZE=256528347L
    private const val SHA="b38b6334cdc5fa228f260710a41082d52a581b29c1a474f87e2a0f70887258fb"
    private fun root(context: Context)=File(context.filesDir,"${AssetManager.MODEL_VERSION}/tera/models")
    fun ready(context: Context): Boolean {
        val root=root(context)
        return File(root,"$TEACHER.onnx").length()==SIZE &&
            File(root,"teacher.verified").let { it.isFile && runCatching { it.readText()==SHA }.getOrDefault(false) }
    }
    fun selected(context: Context): String =
        if(context.getSharedPreferences("SupertonicPrefs",0).getBoolean("tera_teacher",false) && ready(context)) TEACHER else FAST
    suspend fun download(context: Context, progress: (Int)->Unit) = ModelDownloadForeground.run(context) {
        val root=root(context).apply { mkdirs() }
        ResumableModelFile.fetch("https://github.com/davnozdu/supertonic-android/releases/download/russian-resources-v1/$TEACHER.onnx",
            File(root,"$TEACHER.onnx"),SIZE,SHA) { done,total -> progress((done*100/total).toInt()) }
        File(root,"teacher.verified").writeText(SHA)
    }
}
