package com.brahmadeo.supertonic.tts.kokoro

import android.content.Context
import android.os.Build
import com.brahmadeo.supertonic.tts.utils.ResumableModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

object KokoroDownload {
    private const val BASE = "https://github.com/davnozdu/supertonic-android/releases/download/kokoro-ru-v2"
    private val lock = Mutex()
    val voices = listOf("sveta", "masha", "dima")
    private val names = setOf("model_quantized.onnx", "model_dima_quantized.onnx", "sveta.bin", "masha.bin", "dima.bin", "config.json", "espeak-data.zip", "NOTICE.txt", "README.txt", "LICENSE_APACHE2.txt", "LICENSE_ESPEAK.txt")
    data class Asset(val name: String, val size: Long, val sha: String)
    private val fullModels = listOf(
        Asset("model.onnx",325634452,"2b784920660089011888fe88f2f48339c01334ad627c91cc0f88d947bc64fcf3"),
        Asset("model_dima.onnx",325634452,"326bc6d2764263c11a8f5cbcfdcf0a072e441bd0d2dcb4875fa3c25724b201c6"))
    private val fullFingerprint = fullModels.joinToString("\n") { it.sha }
    fun fullReady(context: Context): Boolean = supported() && runCatching {
        val dir = root(context)
        File(dir,"full.verified.sha256").readText() == fullFingerprint &&
            fullModels.all { File(dir,it.name).length() == it.size }
    }.getOrDefault(false)
    fun fullEnabled(context: Context) = context.getSharedPreferences("SupertonicPrefs",0)
        .getBoolean("kokoro_full_precision",true) && fullReady(context)
    suspend fun downloadFull(context: Context, progress: (String,Float) -> Unit) =
        com.brahmadeo.supertonic.tts.utils.ModelDownloadForeground.run(context) {
            withContext(Dispatchers.IO) { lock.withLock {
                require(supported())
                if(fullReady(context)) return@withLock
                val dir=root(context).apply { mkdirs() }
                val total=fullModels.sumOf { it.size };var complete=0L
                for(asset in fullModels) {
                    ResumableModelFile.fetch("$BASE/${asset.name}",File(dir,asset.name),asset.size,asset.sha) { received,_ ->
                        progress("Kokoro: ${(complete+received)/1048576} / ${total/1048576} МБ",(complete+received).toFloat()/total)
                    }
                    complete+=asset.size
                }
                File(dir,"full.verified.sha256").writeText(fullFingerprint)
                com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
                progress("Полная точность Kokoro установлена",1f)
            } }
        }
    fun supported() = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
    fun root(context: Context) = File(context.filesDir, "kokoro-ru-v2")
    fun label(voice: String) = when (voice) { "dima" -> "Дима · мужской"; "masha" -> "Маша · женский"; else -> "Света · женский" }
    fun voiceFile(context: Context, selected: String): File = File(root(context),
        (File(selected).name.removeSuffix(".json").removeSuffix(".bin").takeIf { it in voices } ?: "sveta") + ".bin")
    private fun manifest(context: Context) = context.assets.open("kokoro_manifest.json").bufferedReader().use { it.readText() }
    private fun fingerprint(context: Context) = MessageDigest.getInstance("SHA-256").digest(manifest(context).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun rows(context: Context, key: String): List<Asset> {
        val data = JSONObject(manifest(context)).getJSONArray(key)
        return List(data.length()) { i -> data.getJSONObject(i).let { Asset(it.getString("name"), it.getLong("size"), it.getString("sha256")) } }
    }
    private fun files(context: Context) = rows(context, "files").also {
        require(it.size == names.size && it.map { row -> row.name }.toSet() == names && it.all { row -> row.size > 0 })
    }
    fun ready(context: Context): Boolean = supported() && runCatching {
        val dir = root(context)
        File(dir, "verified.sha256").readText() == fingerprint(context) &&
            (files(context) + rows(context, "espeak_entries")).all { File(dir, it.name).length() == it.size }
    }.getOrDefault(false)
    suspend fun download(context: Context, progress: (String, Float) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            require(supported()) { "Kokoro-RU требует ARM64" }
            if (ready(context)) { progress("Kokoro-RU готова", 1f); return@withLock }
            val dir = root(context).apply { mkdirs() }
            val assets = files(context); val total = assets.sumOf { it.size }; var complete = 0L
            for (asset in assets) {
                ResumableModelFile.fetch("$BASE/${asset.name}", File(dir, asset.name), asset.size, asset.sha) { received, _ ->
                    progress("Kokoro-RU: ${(complete + received) / 1048576} / ${total / 1048576} МБ", (complete + received).toFloat() / total * .98f)
                }
                complete += asset.size
            }
            val entries = rows(context, "espeak_entries").associateBy { it.name }
            val seen = mutableSetOf<String>()
            ZipInputStream(File(dir, "espeak-data.zip").inputStream()).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    val expected = entries[entry.name] ?: error("Неизвестный файл фонемизатора")
                    require(seen.add(entry.name) && !entry.isDirectory && expected.size in 1..2_000_000)
                    val target = File(dir, entry.name)
                    require(target.canonicalPath.startsWith(dir.canonicalPath + File.separator))
                    target.parentFile!!.mkdirs()
                    val temporary = File(target.path + ".part")
                    val hash = MessageDigest.getInstance("SHA-256"); var count = 0L
                    temporary.outputStream().use { out ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = zip.read(buffer); if (n < 0) break
                            count += n; require(count <= expected.size)
                            hash.update(buffer, 0, n); out.write(buffer, 0, n)
                        }
                    }
                    require(count == expected.size && hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected.sha)
                    check(temporary.renameTo(target))
                }
            }
            require(seen == entries.keys)
            File(dir, "verified.sha256").writeText(fingerprint(context))
            progress("Kokoro-RU готова · 3 голоса", 1f)
        }
    }
}
