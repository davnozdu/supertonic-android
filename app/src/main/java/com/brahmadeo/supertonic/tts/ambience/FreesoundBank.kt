package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Preparation-only lookup. No API calls from playback, sync or UI; reusable AAC and credits in one index. */
object FreesoundBank {
    private const val MAX_AUDIO = 8 * 1024 * 1024
    private const val DAY = 86_400_000L
    private var lastSearch = 0L
    private var blockedUntil = 0L
    private fun root(context: Context) = File(context.filesDir, "freesound-bank")
    private fun index(context: Context) = File(root(context), "index.json")
    private fun read(context: Context) = runCatching { JSONObject(index(context).readText()) }.getOrDefault(JSONObject())
    private fun save(context: Context, json: JSONObject) {
        root(context).mkdirs()
        val part = File(root(context), "index.json.part"); part.writeText(json.toString()); check(part.renameTo(index(context)))
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    @Synchronized fun remove(context: Context) { root(context).deleteRecursively() }
    @Synchronized fun credentialsChanged() { blockedUntil = 0L }
    fun bytes(context: Context) = root(context).listFiles()?.sumOf { it.length() } ?: 0L

    /** [allowNetwork] is true only during book preparation. Cached audio still requires the opt-in switch. */
    @Synchronized fun find(context: Context, kind: String, prompt: String, event: Boolean,
                           allowNetwork: Boolean = false, cancelled: () -> Boolean = { false }, music: Boolean = false): File? {
        if (!FreesoundSettings.enabled(context) || cancelled()) return null
        val key = hash(if (music) "music-v1|${prompt.trim().lowercase()}" else "v1|$event|$kind|${prompt.trim().lowercase()}")
        val db = read(context); val row = db.optJSONObject(key)
        row?.optString("file")?.takeIf { it.matches(Regex("[a-f0-9]+\\.m4a")) }?.let { name ->
            File(root(context), name).takeIf { it.isFile && it.length() > 0 }?.let { return it }
        }
        if (!allowNetwork || System.currentTimeMillis() < blockedUntil) return null
        if (row != null && System.currentTimeMillis() - row.optLong("checked") < DAY) return null
        val apiKey = FreesoundSettings.key(context).takeIf { it.isNotBlank() } ?: return null
        val query = if (music) MusicMatch.query(prompt) ?: return null else if (event) kind.replace('_', ' ') else {
            val place = OpenBankMatch.habitat(prompt) ?: OpenBankMatch.concepts(prompt).firstOrNull()
            when (place) {
                "apartment", "office", "kitchen" -> "$place room tone"
                "train" -> "train interior ambience"
                null -> BankText.tokens(prompt.substringBefore('|')).take(5).joinToString(" ") + " ambience"
                else -> "$place ambience"
            }
        }
        val filter = "duration:[${if (event) "0.4 TO 30" else "30 TO 90"}]"
        val params = mapOf("query" to query, "filter" to filter, "page_size" to "15", "sort" to "rating_desc",
            "fields" to "id,name,description,tags,username,license,previews,duration")
            .entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        var downloaded: File? = null
        var output: File? = null
        try {
            val wait = 1100 - (System.nanoTime() / 1_000_000 - lastSearch)
            if (wait > 0) Thread.sleep(wait)
            if (!FreesoundSettings.enabled(context) || cancelled()) return null
            lastSearch = System.nanoTime() / 1_000_000
            val response = fetch("https://freesound.org/apiv2/search/?$params", apiKey, 512 * 1024)
            val results = JSONObject(String(response, Charsets.UTF_8)).getJSONArray("results")
            val candidates = (0 until results.length()).map { results.getJSONObject(it) }.filter { s ->
                FreesoundMatch.allowedLicense(s.optString("license")) && s.optDouble("duration") in (if (event) 0.4..30.0 else 30.0..90.0)
            }
            val selected = if (music) candidates.firstOrNull { s ->
                MusicMatch.matches(prompt, s.optString("name"), s.optString("description"), tags(s))
            } else if (event) candidates.firstOrNull { s ->
                FreesoundMatch.event(kind, s.optString("name"), tags(s))
            } else {
                val scenes = candidates.map { s -> OpenBankMatch.Scene(s.getLong("id").toString(),
                    OpenBankMatch.concepts(s.optString("name") + " " + tags(s).joinToString(" ")),
                    BankText.tokens(tags(s).joinToString(" ")), s.optString("name"), s.optDouble("duration")) }
                val id = OpenBankMatch.scenes(prompt, scenes).firstOrNull()
                candidates.firstOrNull { it.getLong("id").toString() == id }
            }
            if (selected == null) {
                db.put(key, JSONObject().put("checked", System.currentTimeMillis()).put("status", "no_confident_match")); save(context, db)
                return null
            }
            if (cancelled() || !FreesoundSettings.enabled(context)) return null
            val preview = selected.getJSONObject("previews").getString("preview-hq-mp3")
            val url = URL(preview)
            require(url.protocol == "https" && url.host == "cdn.freesound.org") { "Unexpected audio host" }
            val bytes = fetch(preview, null, MAX_AUDIO)
            root(context).mkdirs()
            downloaded = File(root(context), "$key.source.part").apply { writeBytes(bytes) }
            output = File(root(context), "$key.m4a")
            FreesoundAudio.prepare(downloaded, output, event, selected.getDouble("duration"), music = music) {
                cancelled() || !FreesoundSettings.enabled(context)
            }
            check(output.isFile && output.length() > 0)
            if (cancelled() || !FreesoundSettings.enabled(context)) throw InterruptedException("cancelled")
            val credit = JSONObject().put("file", output.name).put("kind", kind).put("prompt", prompt).put("event", event)
                .put("music", music).put("id", selected.getLong("id")).put("title", selected.getString("name"))
                .put("author", selected.getString("username")).put("license", selected.getString("license"))
                .put("source_url", "https://freesound.org/s/${selected.getLong("id")}/")
                .put("description", selected.optString("description")).put("checked", System.currentTimeMillis())
                .put("changes", "Event window selection where applicable; loudness normalization; AAC encoding")
            db.put(key, credit); save(context, db)
            Log.i("Ambience", "Freesound cached id=${selected.getLong("id")} event=$event music=$music")
            return output
        } catch (e: InterruptedException) {
            output?.delete(); throw e
        } catch (e: Exception) {
            output?.delete()
            if (System.currentTimeMillis() >= blockedUntil) blockedUntil = System.currentTimeMillis() + 60_000
            Log.w("Ambience", "Freesound unavailable: ${e.javaClass.simpleName}; fallback")
            return null
        } finally {
            downloaded?.delete()
            output?.let { File(it.path + ".part").delete() }
        }
    }
    private fun tags(s: JSONObject): List<String> = s.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    private fun fetch(url: String, apiKey: String?, limit: Int): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = apiKey == null
            connection.connectTimeout = 3000; connection.readTimeout = 4000
            connection.setRequestProperty("User-Agent", "MyTTS/Freesound")
            apiKey?.let { connection.setRequestProperty("Authorization", "Token $it") }
            val status = connection.responseCode
            if (status == 429) {
                val retry = connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(60L, 86_400L) ?: 3600L
                blockedUntil = System.currentTimeMillis() + retry * 1000
            }
            check(status == 200) { "HTTP $status" }
            if (connection.contentLengthLong > limit) error("Oversized response")
            return connection.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16384)
                val deadline = System.nanoTime() + 8_000_000_000L
                while (true) {
                    check(System.nanoTime() < deadline) { "Download deadline" }
                    val n = stream.read(buffer); if (n < 0) break
                    check(out.size() + n <= limit); out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } finally { connection.disconnect() }
    }
}
