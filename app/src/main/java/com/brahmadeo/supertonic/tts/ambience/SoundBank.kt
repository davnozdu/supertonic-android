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

    /** Тумблер «Брать готовые звуки из базы» (по умолчанию включён). */
    fun on(context: Context): Boolean =
        AmbienceSettings.soundBank(context.getSharedPreferences("SupertonicPrefs", 0))

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

    fun size(context: Context): Int = (manifest(context).keys + local(context).keys).size

    /** Свои и импортированные клипы: вид -> файл (files/sound-bank/index-local.json). */
    fun local(context: Context): Map<String, String> {
        val file = File(dir(context), "index-local.json")
        if (!file.isFile) return emptyMap()
        return runCatching {
            val o = JSONObject(file.readText())
            o.keys().asSequence().mapNotNull { k ->
                val v = o.opt(k)
                val name = when (v) {
                    is JSONObject -> v.optString("file")
                    is String -> v
                    else -> ""
                }
                if (name.isEmpty()) null else k to name
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    /** Клип вида [kind] из базы: уже скачанный или скачанный сейчас (null — нет в базе или нет сети). */
    fun clip(context: Context, kind: String): File? {
        // Своя (и импортированная) часть базы: файл уже на телефоне.
        local(context)[kind]?.let { file -> File(dir(context), file).takeIf { it.isFile && it.length() > 0L } }?.let { return it }
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
    fun store(context: Context, kind: String, file: File, custom: Boolean = false) {
        if (kind.isEmpty() || !file.isFile || file.length() == 0L) return
        runCatching {
            val dir = dir(context).apply { mkdirs() }
            val target = File(dir, "$kind.$FORMAT")
            if (!target.isFile || target.length() != file.length()) file.copyTo(target, overwrite = true)
            val json = File(dir, "index-local.json").let {
                if (it.isFile) runCatching { JSONObject(it.readText()) }.getOrDefault(JSONObject()) else JSONObject()
            }
            json.put(kind, JSONObject().put("file", "$kind.$FORMAT").put("custom", custom))
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



    /** Клип по виду, а если такого вида в базе нет — по смыслу описания: слова промпта сравниваются со словами
     * описаний базы (не меньше двух третей общих). Так книга не платит генерацией за другое имя того же звука. */
    fun find(context: Context, kind: String, prompt: String): File? {
        clip(context, kind)?.let { return it }
        val words = tokens(prompt)
        if (words.isEmpty()) return null
        var best: String? = null; var bestScore = 0f
        for ((key, value) in manifest(context)) {
            val other = tokens(value.first.removeSuffix(".$FORMAT").replace('_', ' '))
            if (other.isEmpty()) continue
            val score = words.count { it in other }.toFloat() / maxOf(words.size, other.size)
            if (score > bestScore) { bestScore = score; best = key }
        }
        return best?.takeIf { bestScore >= 0.66f }?.let { clip(context, it) }
    }

    private fun tokens(text: String): Set<String> = text.lowercase()
        .split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in STOP }.toSet()

    private val STOP = setOf("the", "and", "with", "for", "from", "into", "onto", "some", "very", "then", "that", "this")

    /** «Выгрузить базу»: все клипы базы одним zip (manifest.json + <вид>.m4a) в выбранный пользователем файл. */
    fun export(context: Context, out: java.io.OutputStream): Int {
        val dir = dir(context)
        val clips = dir.listFiles { f -> f.isFile && f.name.endsWith(".$FORMAT") }?.sortedBy { it.name }.orEmpty()
        val sounds = org.json.JSONArray()
        val zip = java.util.zip.ZipOutputStream(out.buffered())
        zip.use { z ->
            for (clip in clips) {
                val key = clip.name.removeSuffix(".$FORMAT")
                val bytes = clip.readBytes()
                sounds.put(JSONObject().put("key", key).put("file", clip.name).put("bytes", clip.length())
                    .put("sha256", sha256(bytes)).put("seconds", 8))
                z.putNextEntry(java.util.zip.ZipEntry(clip.name))
                z.write(bytes)
                z.closeEntry()
            }
            z.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            z.write(JSONObject().put("version", 1).put("sounds", sounds).toString().toByteArray())
            z.closeEntry()
        }
        return clips.size
    }

    /** «Импортировать базу»: zip с manifest.json и клипами. [replace] — стереть текущую базу, иначе слить;
     * дубли (тот же вид и тот же sha256) пропускаются, отличающийся файл того же вида при слиянии не затирается. */
    fun importZip(context: Context, input: java.io.InputStream, replace: Boolean): Pair<Int, Int> {
        val dir = dir(context).apply { mkdirs() }
        var added = 0; var skipped = 0
        val local = File(dir, "index-local.json").let {
            if (it.isFile) runCatching { JSONObject(it.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        }
        if (replace) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".$FORMAT") }?.forEach { it.delete() }
            local.keys().asSequence().toList().forEach { local.remove(it) }
        }
        java.util.zip.ZipInputStream(input.buffered()).use { z ->
            var entry = z.nextEntry
            while (entry != null) {
                val name = File(entry.name).name
                if (name.endsWith(".$FORMAT")) {
                    val bytes = z.readBytes()
                    val key = name.removeSuffix(".$FORMAT")
                    val target = File(dir, name)
                    val same = target.isFile && target.length() == bytes.size.toLong() && sha256(target.readBytes()) == sha256(bytes)
                    if (same) skipped++
                    else if (!replace && target.isFile) skipped++      // другой звук того же вида — не затираем
                    else { target.writeBytes(bytes); local.put(key, JSONObject().put("file", name).put("custom", true)); added++ }
                }
                z.closeEntry()
                entry = z.nextEntry
            }
        }
        File(dir, "index-local.json").writeText(local.toString())
        Log.i("SoundBank", "import: +$added, skipped $skipped, replace=$replace")
        return added to skipped
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
