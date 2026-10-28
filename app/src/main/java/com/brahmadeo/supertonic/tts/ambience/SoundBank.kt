package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** «База звуков»: готовые клипы событий, сделанные один раз для всех книг. Один клип на вид действия (8 с, AAC).
 *
 * Архива нет: manifest.json и каждый клип лежат на GitHub отдельными файлами, поэтому приложение берёт ровно те
 * звуки, которые нужны книге, и ничего не распаковывает — скачанный клип копируется в папку книги, генерации ноль.
 * Всё, что пришлось сделать для книги самой, дописывается в базу, поэтому она растёт от книги к книге и в следующий
 * раз этот звук уже бесплатный. */
object SoundBank {
    private const val BASE = "https://github.com/davnozdu/supertonic-android/releases/download/sound-bank-v1"
    private const val FORMAT = "m4a"
    private const val MAX_CLIP = 4L * 1024 * 1024

    fun dir(context: Context): File = File(context.filesDir, "sound-bank")

    /** Список базы: вид действия -> (файл, sha256). Кэшируется в files/sound-bank/manifest.json. */
    fun manifest(context: Context, refresh: Boolean = false): Map<String, Pair<String, String>> {
        val file = File(dir(context), "manifest.json")
        if (!file.isFile || refresh) {
            val text = fetch("$BASE/manifest.json", 8L * 1024 * 1024)
            if (text != null) runCatching { dir(context).mkdirs(); file.writeText(String(text, Charsets.UTF_8)) }
        }
        if (!file.isFile) return emptyMap()
        return runCatching {
            val sounds = JSONObject(file.readText()).optJSONArray("sounds") ?: return emptyMap()
            (0 until sounds.length()).mapNotNull { i ->
                val s = sounds.optJSONObject(i) ?: return@mapNotNull null
                val key = s.optString("key").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                key to (s.optString("file").ifEmpty { "$key.$FORMAT" } to s.optString("sha256"))
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    fun size(context: Context): Int = manifest(context).size

    /** Клип вида [kind] из базы: уже скачанный или скачанный сейчас (null — нет в базе или нет сети). */
    fun clip(context: Context, kind: String): File? {
        val (file, sha) = manifest(context)[kind] ?: return null
        val target = File(dir(context), file)
        if (target.isFile && target.length() > 0L) return target
        val bytes = fetch("$BASE/$file", MAX_CLIP) ?: return null
        if (sha.isNotEmpty() && sha256(bytes) != sha) {
            Log.w("SoundBank", "$file: sha256 не совпал, клип не взят")
            return null
        }
        return runCatching { target.writeBytes(bytes); target }
            .onFailure { Log.w("SoundBank", "save $file: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /** Кладёт клип книги в базу (база растёт) — его возьмут следующие книги. */
    fun store(context: Context, kind: String, file: File) {
        if (kind.isEmpty() || !file.isFile || file.length() == 0L) return
        runCatching {
            val dir = dir(context).apply { mkdirs() }
            val target = File(dir, "$kind.$FORMAT")
            if (!target.isFile || target.length() != file.length()) file.copyTo(target, overwrite = true)
            val json = File(dir, "index-local.json").let {
                if (it.isFile) runCatching { JSONObject(it.readText()) }.getOrDefault(JSONObject()) else JSONObject()
            }
            json.put(kind, "$kind.$FORMAT")
            File(dir, "index-local.json").writeText(json.toString())
            Log.i("SoundBank", "kept $kind for the next books")
        }.onFailure { Log.w("SoundBank", "store $kind: ${it.javaClass.simpleName}") }
    }

    /** Скачать всю базу сразу (по желанию): каждый файл отдельно, sha256 проверяется. Возвращает число взятых. */
    fun downloadAll(context: Context, progress: (Int, Int, String) -> Unit = { _, _, _ -> }): Int {
        val list = manifest(context, refresh = true)
        var done = 0
        for ((kind, _) in list) {
            progress(done, list.size, kind)
            if (clip(context, kind) != null) done++
        }
        progress(done, list.size, "")
        return done
    }

    private fun fetch(url: String, limit: Long): ByteArray? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 40_000
        }
        try {
            if (connection.responseCode !in 200..299) return null
            require(connection.contentLengthLong.coerceAtLeast(0L) <= limit) { "слишком большой файл" }
            connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
