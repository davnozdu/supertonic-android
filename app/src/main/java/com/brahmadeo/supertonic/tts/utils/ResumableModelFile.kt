package com.brahmadeo.supertonic.tts.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Bounded, resumable HTTP transfer. Never installs data before full size/SHA validation. */
object ResumableModelFile {
    suspend fun fetch(url: String, file: File, expectedSize: Long, sha: String, progress: (Long,Long)->Unit) {
        if(file.length()>expectedSize) file.delete()
        var last: IOException?=null
        for(attempt in 0..2) {
            coroutineContext.ensureActive()
            if(file.length()==expectedSize) break
            val c=URL(url).openConnection() as HttpURLConnection
            c.connectTimeout=15000;c.readTimeout=30000
            var offset=file.length()
            if(offset>0) c.setRequestProperty("Range","bytes=$offset-")
            c.setRequestProperty("Accept-Encoding","identity")
            try {
                val status=c.responseCode
                check(status==200 || status==206) { "Загрузка модели: HTTP $status" }
                if(status==200 && offset>0) { check(file.delete());offset=0 }
                if(status==206) check(c.getHeaderField("Content-Range")?.startsWith("bytes $offset-")==true) { "Некорректный ответ Range" }
                var received=offset;var lastReported=offset
                c.inputStream.use { input -> FileOutputStream(file,offset>0).use { output ->
                    val buffer=ByteArray(65536)
                    while(true) {
                        coroutineContext.ensureActive()
                        val n=input.read(buffer);if(n<0) break
                        received+=n;check(received<=expectedSize) { "Некорректный размер модели" }
                        output.write(buffer,0,n)
                        if(received-lastReported>=262144) { progress(received,expectedSize);lastReported=received }
                    }
                } }
                if(file.length()!=expectedSize) throw IOException("Неполная загрузка модели")
                break
            } catch(e: IOException) {
                last=e
                if(attempt==2) throw e
            } finally { c.disconnect() }
            delay((attempt+1)*700L)
        }
        check(file.length()==expectedSize) { last?.message ?: "Неполная загрузка модели" }
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(65536);while(true) {
            coroutineContext.ensureActive();val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)
        } }
        val actual=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if(actual!=sha) { file.delete();error("Контрольная сумма модели не совпала: повторите загрузку") }
        progress(expectedSize,expectedSize)
    }
}
