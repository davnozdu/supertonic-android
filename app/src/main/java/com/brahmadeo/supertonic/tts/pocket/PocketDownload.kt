package com.brahmadeo.supertonic.tts.pocket

import android.content.Context
import android.os.Build
import com.brahmadeo.supertonic.tts.utils.ResumableModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object PocketDownload {
    private const val BASE="https://github.com/davnozdu/supertonic-android/releases/download/shtorm-pocket-v2"
    private val lock=Mutex()
    private val names=setOf("mimi_encoder.onnx","text_conditioner.onnx","flow_lm_main.onnx","flow_lm_flow.onnx","mimi_decoder.onnx","tokenizer.model","alba.wav","LICENSE.txt")
    data class Asset(val name: String,val size: Long,val sha: String)
    fun supported() = Build.SUPPORTED_ABIS.firstOrNull()=="arm64-v8a"
    fun root(context: Context)=File(context.filesDir,"shtorm-pocket-v2")
    private fun manifest(context: Context)=context.assets.open("shtorm_manifest.json").bufferedReader().use { it.readText() }
    private fun fingerprint(context: Context)=MessageDigest.getInstance("SHA-256").digest(manifest(context).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun files(context: Context): List<Asset> {
        val data=JSONObject(manifest(context)).getJSONArray("files")
        return List(data.length()) { index ->
            val row=data.getJSONObject(index)
            Asset(row.getString("name"),row.getLong("size"),row.getString("sha256")).also { require(it.name in names && it.size>0) }
        }.also { require(it.map { file -> file.name }.toSet()==names && it.size==names.size) }
    }
    fun ready(context: Context): Boolean = supported() && runCatching {
        File(root(context),"verified.sha256").readText()==fingerprint(context) && files(context).all { File(root(context),it.name).length()==it.size }
    }.getOrDefault(false)
    suspend fun download(context: Context, progress: (String,Float)->Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            require(supported()) { "Shtorm PocketTTS требует ARM64" }
            if(ready(context)) { progress("Shtorm готова",1f);return@withLock }
            val dir=root(context).apply { mkdirs() }
            val assets=files(context)
            val total=assets.sumOf { it.size };var complete=0L
            for(asset in assets) {
                val target=File(dir,asset.name)
                ResumableModelFile.fetch("$BASE/${asset.name}",target,asset.size,asset.sha) { received,_ ->
                    progress("Shtorm: ${(complete+received)/1048576} / ${total/1048576} МБ",(complete+received).toFloat()/total)
                }
                complete+=asset.size
            }
            File(dir,"verified.sha256").writeText(fingerprint(context))
            progress("Shtorm готова",1f)
        }
    }
}
