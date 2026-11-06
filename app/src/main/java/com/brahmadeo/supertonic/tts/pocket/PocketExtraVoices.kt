package com.brahmadeo.supertonic.tts.pocket

import android.content.Context
import com.brahmadeo.supertonic.tts.utils.ResumableModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** User-supplied Russian references. Kept as MP3: Pocket's native decoder reads them directly. */
object PocketExtraVoices {
    private const val BASE="https://github.com/davnozdu/supertonic-android/releases/download/shtorm-extra-voices-v1"
    private val lock=Mutex()
    data class Asset(val name:String,val label:String,val size:Long,val sha:String)
    fun assets(ctx:Context):List<Asset> {
        val a=JSONObject(ctx.assets.open("shtorm_extra_voices.json").bufferedReader().use { it.readText() }).getJSONArray("files")
        return List(a.length()) { i -> val r=a.getJSONObject(i);Asset(r.getString("name"),r.getString("label"),r.getLong("size"),r.getString("sha256")) }
    }
    fun isExtra(name:String)=name.startsWith("pack_")
    fun file(ctx:Context,name:String)=File(File(PocketDownload.root(ctx),"extra-voices-v1"),"$name.mp3")
    fun ready(ctx:Context,name:String):Boolean {
        val a=assets(ctx).firstOrNull { it.name==name } ?: return false
        return file(ctx,name).length()==a.size && File(file(ctx,name).path+".sha256").takeIf { it.isFile }?.readText()==a.sha
    }
    fun installed(ctx:Context)=assets(ctx).filter { ready(ctx,it.name) }.map { it.name }
    fun label(ctx:Context,name:String)=assets(ctx).firstOrNull { it.name==name }?.label ?: name
    suspend fun ensure(ctx:Context,name:String,progress:(String,Float)->Unit)=withContext(Dispatchers.IO) {
        lock.withLock {
            val a=assets(ctx).firstOrNull { it.name==name } ?: error("Неизвестный голос Shtorm")
            if(!ready(ctx,name)) {
                check(file(ctx,name).parentFile!!.let { it.isDirectory || it.mkdirs() })
                ResumableModelFile.fetch("$BASE/$name.mp3",file(ctx,name),a.size,a.sha) { n,total -> progress("Голос ${a.label}",n.toFloat()/total) }
                File(file(ctx,name).path+".sha256").writeText(a.sha)
            }
        }
    }
    fun delete(ctx:Context,name:String) { file(ctx,name).delete();File(file(ctx,name).path+".sha256").delete() }
}
