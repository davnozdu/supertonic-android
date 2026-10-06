package com.brahmadeo.supertonic.tts.music

import android.content.Context
import com.brahmadeo.supertonic.tts.utils.ModelDownloadForeground
import com.brahmadeo.supertonic.tts.utils.ResumableModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/** Pinned catalog: downloads never become selectable until checksum verification succeeds. */
object MusicCatalog {
    data class Track(val id: String, val file: String, val title: String, val size: Long, val sha: String)
    private val lock = Mutex()
    private const val BASE = "https://github.com/davnozdu/supertonic-android/releases/download/reading-music-v1"
    fun tracks(context: Context): List<Track> = context.assets.open("reading_music_catalog.json").bufferedReader().use {
        val array = JSONArray(it.readText())
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Track(item.getString("id"), item.getString("file"), item.getString("title"), item.getLong("size"), item.getString("sha256"))
                .also { track -> require(track.file.matches(Regex("[A-Za-z0-9_]+\\.mp3")) && track.id.matches(Regex("[a-z0-9_]+"))) }
        }
    }
    private fun directory(context: Context) = File(context.filesDir, "background_music/ready").apply { mkdirs() }
    private fun path(context: Context, track: Track) = File(directory(context),track.file)
    private fun marker(context: Context, track: Track) = File(directory(context),"${track.file}.verified")
    fun ready(context: Context, track: Track): File? = path(context,track).takeIf {
        it.isFile && it.length()==track.size && runCatching { marker(context,track).readText()==track.sha }.getOrDefault(false)
    }
    fun selected(context: Context, id: String): File? = tracks(context).firstOrNull { it.id==id }?.let { ready(context,it) }
    /** No choice yet, or the chosen file is gone: fall back to the first installed track. "" when none is installed. */
    fun ensureDefault(context: Context): String {
        val prefs = context.getSharedPreferences("SupertonicPrefs",0)
        val current = prefs.getString("background_music_track","").orEmpty()
        val valid = when {
            current == "custom" -> MusicFiles.custom(context) != null
            current.startsWith("ready:") -> selected(context, current.removePrefix("ready:")) != null
            else -> false
        }
        if (valid) return current
        val first = tracks(context).firstOrNull { ready(context,it) != null } ?: return ""
        return "ready:${first.id}".also { prefs.edit().putString("background_music_track", it).apply() }
    }
    suspend fun download(context: Context, progress: (Int,String)->Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            ModelDownloadForeground.run(context) {
                val missing = tracks(context).filter { ready(context,it)==null }
                val total = missing.sumOf { it.size }.coerceAtLeast(1L)
                var complete = 0L
                for(track in missing) {
                    val partial = File(directory(context),"${track.file}.partial")
                    ResumableModelFile.fetch("$BASE/${track.file}",partial,track.size,track.sha) { bytes,_ ->
                        progress(((complete+bytes)*100/total).toInt(),track.title)
                    }
                    check(partial.renameTo(path(context,track))) { "Не удалось сохранить композицию" }
                    marker(context,track).writeText(track.sha)
                    complete += track.size
                }
                progress(100,"Музыка загружена")
            }
        }
    }
    suspend fun remove(context: Context, track: Track) = withContext(Dispatchers.IO) {
        lock.withLock {
            val prefs = context.getSharedPreferences("SupertonicPrefs",0)
            val wasSelected = prefs.getString("background_music_track","")=="ready:${track.id}"
            if(wasSelected) check(prefs.edit().remove("background_music_track").commit())
            check(!path(context,track).exists() || path(context,track).delete()) { "Не удалось удалить композицию" }
            marker(context,track).delete()
            File(directory(context),"${track.file}.partial").delete()
            // The removed track was playing: continue with another installed one, or switch music off.
            if(wasSelected && ensureDefault(context).isEmpty()) check(prefs.edit().putBoolean("background_music_enabled",false).commit())
            Unit
        }
    }
}
