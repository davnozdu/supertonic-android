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
    private const val SLOW_BASE="https://github.com/davnozdu/supertonic-android/releases/download/shtorm-pocket-v2-slow"
    const val SLOW_KEY="shtorm_slow"
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
    /** v2 slow (calmer delivery): only the graphs that differ from fast live in slow/, the rest are links to the
     * fast files. Its own folder also keeps the voices' KV cache apart, since that state depends on the weights. */
    fun slowRoot(context: Context)=File(root(context),"slow")
    private fun slowManifest(context: Context)=context.assets.open("shtorm_slow_manifest.json").bufferedReader().use { it.readText() }
    private fun slowFiles(context: Context): List<Asset> {
        val data=JSONObject(slowManifest(context)).getJSONArray("files")
        return List(data.length()) { index ->
            val row=data.getJSONObject(index)
            Asset(row.getString("name"),row.getLong("size"),row.getString("sha256")).also { require(it.name in names && it.size>0) }
        }
    }
    private fun slowFingerprint(context: Context)=MessageDigest.getInstance("SHA-256").digest((manifest(context)+slowManifest(context)).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun slowReady(context: Context): Boolean = ready(context) && runCatching {
        val dir=slowRoot(context)
        File(dir,"verified.sha256").readText()==slowFingerprint(context) && slowFiles(context).all { File(dir,it.name).length()==it.size } &&
            names.all { File(dir,it).length()>0 }
    }.getOrDefault(false)
    fun slowSizeMb(context: Context)=runCatching { slowFiles(context).sumOf { it.size }/1048576 }.getOrDefault(0L)
    fun slowEnabled(context: Context)=context.getSharedPreferences("SupertonicPrefs",0).getBoolean(SLOW_KEY,false) && slowReady(context)
    /** Folder the native runtime loads (models, tokenizer and the voice cache). */
    fun modelDir(context: Context, slow: Boolean=slowEnabled(context))=if(slow) slowRoot(context) else root(context)
    suspend fun downloadSlow(context: Context, progress: (String,Float)->Unit) =
        com.brahmadeo.supertonic.tts.utils.ModelDownloadForeground.run(context) {
            withContext(Dispatchers.IO) { lock.withLock {
                require(ready(context)) { "Сначала скачайте Shtorm" }
                if(slowReady(context)) { progress("Спокойная подача установлена",1f);return@withLock }
                val dir=slowRoot(context).apply { mkdirs() }
                val assets=slowFiles(context)
                val total=assets.sumOf { it.size };var complete=0L
                for(asset in assets) {
                    ResumableModelFile.fetch("$SLOW_BASE/${asset.name}",File(dir,asset.name),asset.size,asset.sha) { received,_ ->
                        progress("Shtorm slow: ${(complete+received)/1048576} / ${total/1048576} МБ",(complete+received).toFloat()/total)
                    }
                    complete+=asset.size
                }
                val own=assets.map { it.name }.toSet()
                for(name in names - own) {
                    val link=File(dir,name)
                    link.delete()
                    android.system.Os.symlink(File(root(context),name).path,link.path)
                }
                File(dir,"verified.sha256").writeText(slowFingerprint(context))
                com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
                progress("Спокойная подача установлена",1f)
            } }
        }
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
