package com.brahmadeo.supertonic.tts.music

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

object MusicFiles {
    private val importLock = Mutex()
    private fun root(context: Context) = File(context.filesDir,"background_music").apply { mkdirs() }
    fun custom(context: Context): File? {
        val path=context.getSharedPreferences("SupertonicPrefs",0).getString("background_music_file",null) ?: return null
        return runCatching { File(path).canonicalFile.takeIf { it.parentFile==root(context).canonicalFile && it.isFile && it.length()>0 } }.getOrNull()
    }
    suspend fun import(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        importLock.withLock {
            val prefs=context.getSharedPreferences("SupertonicPrefs",0)
            val old=custom(context)
            val name=runCatching { context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                if(it.moveToFirst()) it.getString(0) else null
            } }.getOrNull() ?: "Свой MP3"
            val file=File.createTempFile("music-",".mp3",root(context))
            var installed=false
            try {
                requireNotNull(context.contentResolver.openInputStream(uri)) { "Файл недоступен" }.use { input ->
                    file.outputStream().use { output ->
                        val buffer=ByteArray(65536)
                        var size=0L
                        while(true) {
                            coroutineContext.ensureActive()
                            val count=input.read(buffer); if(count<0) break
                            size+=count
                            require(size<=2L*1024*1024*1024) { "MP3 больше 2 ГБ" }
                            output.write(buffer,0,count)
                        }
                    }
                }
                val extractor=MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    require((0 until extractor.trackCount).any {
                        extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)=="audio/mpeg"
                    }) { "Выберите корректный MP3-файл" }
                } finally { extractor.release() }
                coroutineContext.ensureActive()
                BackgroundMusic.initialize(context)
                check(prefs.edit().putString("background_music_file",file.absolutePath)
                    .putString("background_music_name",name).putString("background_music_track","custom")
                    .putBoolean("background_music_enabled",true).commit()) { "Не удалось сохранить настройку" }
                installed=true
                old?.delete()
                name
            } finally { if(!installed) file.delete() }
        }
    }
    suspend fun remove(context: Context) = withContext(Dispatchers.IO) {
        importLock.withLock {
            val old=custom(context)
            val prefs=context.getSharedPreferences("SupertonicPrefs",0)
            val editor=prefs.edit().remove("background_music_file").remove("background_music_name")
            if(prefs.getString("background_music_track","")=="custom") editor.remove("background_music_track").putBoolean("background_music_enabled",false)
            check(editor.commit()) { "Не удалось удалить настройку" }
            old?.delete()
            Unit
        }
    }
}
