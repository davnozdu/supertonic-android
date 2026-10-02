package com.brahmadeo.supertonic.tts.silero

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

object SileroDownload {
    private const val SHA = "9b04263adc838d33050286cc3705bf0a4867605ed697e29b7db61531d2d82761"
    private const val URL_PACK = "https://github.com/kost-t-human/ruvoice-tts/releases/download/packs/ruvoice-pack-ru.zip"
    private val sizes = mapOf("pack.json" to 935L, "tts_mel.ptl" to 28152216L,
        "backbone.pte" to 53380480L, "head.ptl" to 4961893L)
    val voices = listOf("aidar", "baya", "kseniya", "eugene", "xenia")
    fun supported() = android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
    fun root(context: Context) = File(context.filesDir, "silero-v5_5_ru")
    fun ready(context: Context): Boolean {
        val dir = root(context)
        return File(dir, "verified.sha256").takeIf { it.isFile }?.readText() == SHA &&
            sizes.all { (name, size) -> File(dir, name).length() == size } &&
            voices.all { File(dir, "$it.json").isFile }
    }
    suspend fun download(context: Context, progress: (String, Float) -> Unit) = withContext(Dispatchers.IO) {
        check(supported()) { "Silero v5.5 requires the ARM64 APK and an ARM64 phone" }
        if (ready(context)) { progress("Silero v5.5 готова", 1f); return@withContext }
        val dir = root(context)
        val stage = File(context.filesDir, "silero-v5_5_ru.part")
        stage.deleteRecursively(); check(stage.mkdirs())
        val archive = File(stage, "download.zip")
        try {
            val connection = URL(URL_PACK).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000; connection.readTimeout = 30000
            try {
                check(connection.responseCode == 200) { "Silero download: HTTP ${connection.responseCode}" }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: 86495507L
                val digest = MessageDigest.getInstance("SHA-256")
                connection.inputStream.use { input -> archive.outputStream().use { output ->
                    val buffer = ByteArray(65536); var received = 0L; var last = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); digest.update(buffer, 0, count); received += count
                        if (received - last >= 262144) {
                            progress("Silero v5.5: ${received / 1048576} / ${total / 1048576} МБ", (received.toFloat() / total * .85f).coerceAtMost(.85f))
                            last = received
                        }
                    }
                } }
                check(digest.digest().joinToString("") { "%02x".format(it) } == SHA) { "Silero checksum mismatch" }
            } finally { connection.disconnect() }
            progress("Проверка и распаковка Silero v5.5", .9f)
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
            voices.forEachIndexed { id, name -> File(stage, "$name.json").writeText("{\"speaker\":$id}") }
            File(stage, "verified.sha256").writeText(SHA)
            coroutineContext.ensureActive()
            dir.deleteRecursively(); check(stage.renameTo(dir)) { "Could not publish Silero files" }
            progress("Silero v5.5 готова", 1f)
        } finally { stage.deleteRecursively() }
    }
}
