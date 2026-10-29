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

    /** Один из вариантов клипа вида: разные seed, чтобы частый звук не повторялся одинаково. */
    data class Variant(val file: String, val sha: String, val url: String)

    /** [file]: основной клип; [sha]: его контрольная сумма; [prompt]: английское описание звука (по нему ищется
     * тот же звук, если модель назвала вид по-другому); [variants]: два-три варианта на выбор. */
    data class Entry(val file: String, val sha: String, val prompt: String, val variants: List<Variant> = emptyList()) {
        fun all(): List<Variant> = variants.ifEmpty { listOf(Variant(file, sha, "$BASE/$file")) }
    }

    private var cache: Map<String, Entry>? = null

    fun dir(context: Context): File = File(context.filesDir, "sound-bank")

    /** Тумблер «Брать готовые звуки из базы» (по умолчанию включён). */
    fun on(context: Context): Boolean =
        AmbienceSettings.soundBank(context.getSharedPreferences("SupertonicPrefs", 0))

    /** Список базы: вид действия -> клип. Кэшируется в files/sound-bank/manifest.json и в памяти. */
    @Synchronized
    fun manifest(context: Context, refresh: Boolean = false): Map<String, Entry> {
        if (!refresh) cache?.let { return it }
        val file = File(dir(context), "manifest.json")
        if (!file.isFile || refresh) {
            val text = fetch("$BASE/manifest.json", 8L * 1024 * 1024)
            if (text != null) runCatching { dir(context).mkdirs(); file.writeText(String(text, Charsets.UTF_8)) }
        }
        val parsed = if (!file.isFile) emptyMap() else runCatching {
            val sounds = JSONObject(file.readText()).optJSONArray("sounds") ?: return emptyMap()
            (0 until sounds.length()).mapNotNull { i ->
                val s = sounds.optJSONObject(i) ?: return@mapNotNull null
                val key = s.optString("key").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val file = s.optString("file").ifEmpty { "$key.$FORMAT" }
                val variants = s.optJSONArray("variants")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val v = a.optJSONObject(i) ?: return@mapNotNull null
                        val name = v.optString("file")
                        if (name.isEmpty()) null else Variant(name, v.optString("sha256"), v.optString("url").ifEmpty { "$BASE/$name" })
                    }
                }.orEmpty()
                key to Entry(file, s.optString("sha256"), s.optString("prompt"), variants)
            }.toMap()
        }.getOrElse { emptyMap() }
        cache = parsed
        return parsed
    }

    /** Есть ли такой вид в базе (скачанный или свой): такой звук берётся даже при одном упоминании. */
    fun known(context: Context, kind: String): Boolean =
        kind.isNotEmpty() && (local(context).containsKey(kind) || manifest(context).containsKey(kind))

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
    fun clip(context: Context, kind: String, salt: Int = 0): File? {
        // Своя (и импортированная) часть базы: файл уже на телефоне.
        local(context)[kind]?.let { name -> File(dir(context), name).takeIf { it.isFile && it.length() > 0L } }?.let { return it }
        val entry = manifest(context)[kind] ?: return null
        val variants = entry.all()
        // Вариант выбирается по виду и книге: одно и то же действие в разных книгах звучит по-разному,
        // а внутри книги — одинаково (один клип на вид).
        val pick = variants[Math.floorMod(kind.hashCode() * 31 + salt, variants.size)]
        File(dir(context), pick.file).takeIf { it.isFile && it.length() > 0L }?.let { return it }
        return download(context, pick)
    }

    /** Клип по виду, а если такого вида в базе нет — по смыслу описания, но осторожно: слова описания книги и
     * описания базы должны совпасть почти целиком (иначе — ничего, звук будет сделан заново). */
    fun find(context: Context, kind: String, prompt: String, salt: Int = 0): File? {
        clip(context, kind, salt)?.let { return it }
        val words = BankText.tokens("$kind $prompt")
        if (words.isEmpty()) return null
        val bank = manifest(context).mapValues { (key, entry) -> "$key ${entry.prompt}" }
        val best = BankText.pick(words, bank) ?: return null
        return clip(context, best.first, salt)
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
        for ((kind, entry) in list) {
            progress(done, list.size, kind)
            for (variant in entry.all()) {
                if (File(dir(context), variant.file).let { it.isFile && it.length() > 0L }) continue
                download(context, variant)
            }
            if (File(dir(context), entry.file).isFile) done++
        }
        progress(done, list.size, "")
        return done
    }

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
                val prompt = manifest(context)[key]?.prompt.orEmpty()
                sounds.put(JSONObject().put("key", key).put("file", clip.name).put("bytes", clip.length())
                    .put("sha256", sha256(bytes)).put("prompt", prompt).put("seconds", 8))
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
                    else {
                        target.writeBytes(bytes)
                        val known = com.brahmadeo.supertonic.tts.books.EventKinds.ALL.any { it.key == key }
                        local.put(key, JSONObject().put("file", name).put("custom", !known))
                        added++
                    }
                }
                z.closeEntry()
                entry = z.nextEntry
            }
        }
        File(dir, "index-local.json").writeText(local.toString())
        cache = null
        Log.i("SoundBank", "import: +$added, skipped $skipped, replace=$replace")
        return added to skipped
    }

    private fun download(context: Context, variant: Variant): File? {
        val bytes = fetch(variant.url, MAX_CLIP) ?: return null
        if (variant.sha.isNotEmpty() && sha256(bytes) != variant.sha) {
            Log.w("SoundBank", "${variant.file}: sha256 не совпал, клип не взят")
            return null
        }
        val target = File(dir(context), variant.file)
        return runCatching { target.writeBytes(bytes); target }
            .onFailure { Log.w("SoundBank", "save ${variant.file}: ${it.javaClass.simpleName}") }
            .getOrNull()
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
