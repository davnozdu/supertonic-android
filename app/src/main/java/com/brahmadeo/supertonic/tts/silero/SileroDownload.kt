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
    private const val RU_SHA = "9b04263adc838d33050286cc3705bf0a4867605ed697e29b7db61531d2d82761"
    private const val BASE = "https://github.com/davnozdu/supertonic-android/releases/download/russian-resources-v1"
    private val ruSizes = mapOf("pack.json" to 935L, "tts_mel.ptl" to 28152216L,
        "backbone.pte" to 53380480L, "head.ptl" to 4961893L)
    private val ruVoices = listOf("aidar", "baya", "kseniya", "eugene", "xenia")
    private const val CIS_SHA = "07d9b4d999852892102cf56ab7b94a6b0ceff204f5fde12c13ed980e4ec2141f"
    private val cisSizes = mapOf("pack.json" to 1931L, "tts_mel.ptl" to 26940195L,
        "backbone.pte" to 53380608L, "head.ptl" to 4962917L)
    private val cisSpeakers = mapOf("ru_aigul" to 1, "ru_albina" to 3, "ru_alexandr" to 5, "ru_alfia" to 7, "ru_alfia2" to 9, "ru_bogdan" to 12, "ru_dmitriy" to 14, "ru_ekaterina" to 16, "ru_vika" to 18, "ru_gamat" to 20, "ru_igor" to 22, "ru_karina" to 24, "ru_kejilgan" to 26, "ru_kermen" to 28, "ru_marat" to 31, "ru_miyau" to 33, "ru_nurgul" to 35, "ru_oksana" to 37, "ru_onaoy" to 39, "ru_ramilia" to 41, "ru_roman" to 43, "ru_safarhuja" to 45, "ru_saida" to 47, "ru_sibday" to 49, "ru_zara" to 51, "ru_zhadyra" to 53, "ru_zhazira" to 55, "ru_zinaida" to 57, "ru_eduard" to 58)
    fun cis(context: Context) = com.brahmadeo.supertonic.tts.utils.AssetManager.getModelType(context) == com.brahmadeo.supertonic.tts.utils.AssetManager.SILERO_CIS_MODEL
    fun voices(context: Context) = if (cis(context)) cisSpeakers.keys.toList() else ruVoices
    fun defaultVoice(context: Context) = if (cis(context)) "ru_alexandr" else "kseniya"
    private fun sha(context: Context) = if (cis(context)) CIS_SHA else RU_SHA
    private fun sizes(context: Context) = if (cis(context)) cisSizes else ruSizes
    private fun title(context: Context) = if (cis(context)) "Silero CIS · 29 голосов" else "Silero v5.5"
    fun supported() = android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
    fun root(context: Context) = File(context.filesDir, if (cis(context)) "silero-cis_ru" else "silero-v5_5_ru")
    fun ready(context: Context): Boolean {
        val dir = root(context)
        return File(dir, "verified.sha256").takeIf { it.isFile }?.readText() == sha(context) &&
            sizes(context).all { (name, size) -> File(dir, name).length() == size } &&
            voices(context).all { File(dir, "$it.json").isFile }
    }
    suspend fun download(context: Context, progress: (String, Float) -> Unit) = com.brahmadeo.supertonic.tts.utils.ModelDownloadForeground.run(context) { withContext(Dispatchers.IO) {
        check(supported()) { "Silero v5.5 requires the ARM64 APK and an ARM64 phone" }
        if (ready(context)) { progress("${title(context)} готова", 1f); return@withContext }
        val sizes = sizes(context)
        val SHA = sha(context)
        val title = title(context)
        val dir = root(context)
        val stage = File(context.filesDir, dir.name + ".part")
        stage.deleteRecursively(); check(stage.mkdirs())
        val archive = File(context.filesDir, dir.name + ".download.zip")
        try {
            com.brahmadeo.supertonic.tts.utils.ResumableModelFile.fetch("$BASE/ruvoice-pack-${if (cis(context)) "cis_ru" else "ru"}.zip",archive,if (cis(context)) 85285099L else 86495507L,SHA) { received,total ->
                progress("$title: ${received / 1048576} / ${total / 1048576} МБ",(received.toFloat()/total*.85f).coerceAtMost(.85f))
            }
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
            voices(context).forEachIndexed { index, name ->
                val id = if (cis(context)) cisSpeakers.getValue(name) else index
                File(stage, "$name.json").writeText("{\"speaker\":$id}")
            }
            File(stage, "verified.sha256").writeText(SHA)
            coroutineContext.ensureActive()
            dir.deleteRecursively(); check(stage.renameTo(dir)) { "Could not publish Silero files" }
            progress("${title(context)} готова", 1f)
        } finally { stage.deleteRecursively() }
    }
} }
