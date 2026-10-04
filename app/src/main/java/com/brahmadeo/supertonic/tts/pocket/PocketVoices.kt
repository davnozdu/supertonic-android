package com.brahmadeo.supertonic.tts.pocket

import android.content.Context
import com.brahmadeo.supertonic.tts.utils.ResumableModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Licensed Kyutai references, mirrored independently from the large Shtorm model. */
object PocketVoices {
    val names=listOf("alba","marius","javert","jean","fantine","cosette","eponine")
    private const val BASE="https://github.com/davnozdu/supertonic-android/releases/download/shtorm-voices-v1"
    private val lock=Mutex()
    private data class Asset(val name: String,val size: Long,val sha: String)
    private fun dir(context: Context)=File(PocketDownload.root(context),"voices-v1")
    private fun files(context: Context): List<Asset> {
        val rows=JSONObject(context.assets.open("shtorm_voices.json").bufferedReader().use { it.readText() }).getJSONArray("files")
        return List(rows.length()) { index ->
            val row=rows.getJSONObject(index)
            Asset(row.getString("name"),row.getLong("size"),row.getString("sha256"))
        }.also { require(it.map { row -> row.name }==names) }
    }
    fun ready(context: Context)=runCatching {
        File(dir(context),"verified").readText()==files(context).joinToString { it.sha } &&
            files(context).all { File(dir(context),"${it.name}.wav").length()==it.size }
    }.getOrDefault(false)
    fun installed(context: Context)=if(ready(context)) names else listOf("alba")
    fun voiceFile(context: Context,selected: String): File {
        val name=File(selected).name.removeSuffix(".json").removeSuffix(".wav")
        if(ready(context) && name in names) return File(dir(context),"$name.wav")
        return File(PocketDownload.root(context),"alba.wav")
    }
    suspend fun download(context: Context,progress: (String,Float)->Unit)=withContext(Dispatchers.IO) {
        lock.withLock {
            val assets=files(context);val total=assets.sumOf { it.size };var complete=0L
            dir(context).mkdirs()
            for(asset in assets) {
                ResumableModelFile.fetch("$BASE/${asset.name}.wav",File(dir(context),"${asset.name}.wav"),asset.size,asset.sha) { received,_ ->
                    progress("Голоса Shtorm: ${asset.name}",(complete+received).toFloat()/total)
                }
                complete+=asset.size
            }
            File(dir(context),"verified").writeText(assets.joinToString { it.sha })
            progress("7 голосов Shtorm готовы",1f)
        }
    }
}
