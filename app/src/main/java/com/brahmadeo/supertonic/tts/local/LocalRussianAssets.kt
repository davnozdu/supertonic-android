package com.brahmadeo.supertonic.tts.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

object LocalRussianAssets {
    private const val SHA = "e0b3407ef5de497d37fbe1705c1e69af20919175bcef0028422d6f9f08233a10"
    private val sizes = mapOf("stress.json" to 2436788L, "yo.sacc" to 12681192L, "accentor.ptl" to 11002183L,
        "homo.ptl" to 48930791L, "silero-stress.LICENSE.txt" to 1075L, "eyo.LICENSE.txt" to 1082L)
    fun root(context: Context) = File(context.filesDir, "russian-local-v1")
    fun ready(context: Context): Boolean = root(context).let { dir ->
        File(dir, "verified.sha256").takeIf { it.isFile }?.readText() == SHA && sizes.all { (n,s) -> File(dir,n).length() == s }
    }
    suspend fun download(context: Context, progress: (String, Float) -> Unit) = withContext(Dispatchers.IO) {
        if (ready(context)) { progress("Локальные ударения и ё готовы", 1f); return@withContext }
        val title = "Локальные ударения и ё"
        val dir = root(context)
        val stage = File(context.filesDir, dir.name + ".part")
        stage.deleteRecursively(); check(stage.mkdirs())
        val archive = File(stage, "download.zip")
        try {
            val connection = URL("https://github.com/davnozdu/supertonic-android/releases/download/russian-resources-v1/russian-local-v1.zip").openConnection() as HttpURLConnection
            connection.connectTimeout = 15000; connection.readTimeout = 30000
            try {
                check(connection.responseCode == 200) { "Загрузка локального акцентора: HTTP ${connection.responseCode}" }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: 38577847L
                val digest = MessageDigest.getInstance("SHA-256")
                connection.inputStream.use { input -> archive.outputStream().use { output ->
                    val buffer = ByteArray(65536); var received = 0L; var last = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); digest.update(buffer, 0, count); received += count
                        if (received - last >= 262144) {
                            progress("$title: ${received / 1048576} / ${total / 1048576} МБ", (received.toFloat() / total * .85f).coerceAtMost(.85f))
                            last = received
                        }
                    }
                } }
                check(digest.digest().joinToString("") { "%02x".format(it) } == sha(context)) { "Silero checksum mismatch" }
            } finally { connection.disconnect() }
            progress("Проверка и распаковка $title", .9f)
            ZipInputStream(archive.inputStream()).use { zip ->
                val seen = mutableSetOf<String>()
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    val expected = sizes[entry.name] ?: error("Unexpected Silero archive entry")
                    check(seen.add(entry.name))
                    val target = File(stage, entry.name)
                    target.outputStream().use { output ->
                        val buffer = ByteArray(65536); var countTotal = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = zip.read(buffer); if (count < 0) break
                            countTotal += count; check(countTotal <= expected)
                            output.write(buffer, 0, count)
                        }
                    }
                    check(target.length() == expected)
                }
                check(seen == sizes.keys)
            }
            archive.delete()
            File(stage, "verified.sha256").writeText(SHA)
            coroutineContext.ensureActive()
            dir.deleteRecursively(); check(stage.renameTo(dir)) { "Could not publish Silero files" }
            progress("Локальные ударения и ё готовы", 1f)
        } finally { stage.deleteRecursively() }
    }
}
