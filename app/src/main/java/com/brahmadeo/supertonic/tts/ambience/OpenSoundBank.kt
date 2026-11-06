package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Real recordings are primary; legacy/generated sounds remain an independent fallback. */
object OpenSoundBank {
    private const val BASE = "https://github.com/davnozdu/supertonic-android/releases/download"
    private const val MAX_META = 8L * 1024 * 1024
    private const val RETRY_MS = 10 * 60_000L
    private data class Clip(val id: String, val file: String, val url: String, val sha: String, val bytes: Long,
                            val kinds: Set<String>, val concepts: Set<String>, val tags: Set<String>,
                            val archiveUrl: String, val archiveBytes: Long, val archiveSha: String)
    private val catalogs = ConcurrentHashMap<String, List<Clip>>()
    private val attempted = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Any>()
    fun enabled(ctx: Context, channel: String): Boolean {
        val prefs = ctx.getSharedPreferences("SupertonicPrefs", 0)
        return if (channel == "events") AmbienceSettings.realEvents(prefs) else AmbienceSettings.realScenes(prefs)
    }
    fun downloaded(ctx: Context, channel: String): Int =
        File(ctx.filesDir, "open-sound-bank/$channel").listFiles()?.count { it.extension == "m4a" } ?: 0

    fun remove(ctx: Context, channel: String) {
        ctx.getSharedPreferences("SupertonicPrefs", 0).edit().putBoolean(
            if (channel == "events") AmbienceSettings.REAL_EVENTS else AmbienceSettings.REAL_SCENES, false).apply()
        catalogs.remove(channel); attempted.remove(channel)
        File(ctx.filesDir, "open-sound-bank/$channel").deleteRecursively()
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith("open-bank-$channel-") }?.forEach { it.delete() }
    }

    private fun dir(ctx: Context, channel: String) = File(ctx.filesDir, "open-sound-bank/$channel").apply { mkdirs() }

    private fun parse(text: String): List<Clip> {
        val root = JSONObject(text)
        require(root.optInt("schema") == 1)
        val sounds = root.getJSONArray("sounds")
        fun strings(o: JSONObject, key: String): Set<String> = o.optJSONArray(key)?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }.toSet()
        }.orEmpty()
        return (0 until sounds.length()).mapNotNull { i ->
            val s = sounds.getJSONObject(i)
            val name = s.getString("file")
            val sha = s.getString("sha256")
            val url = s.optString("url")
            val archive = s.optJSONObject("archive")
            val archiveUrl = archive?.optString("url").orEmpty()
            val archiveBytes = archive?.optLong("bytes") ?: 0L
            val archiveSha = archive?.optString("sha256").orEmpty()
            val bytes = s.optLong("bytes")
            if (File(name).name != name || !name.endsWith(".m4a") || !sha.matches(Regex("[a-fA-F0-9]{64}")) ||
                (url.isNotEmpty() && !url.startsWith("$BASE/")) ||
                (url.isEmpty() && (!archiveUrl.startsWith("$BASE/") || archiveBytes !in 1..1024L * 1024 * 1024 ||
                    !archiveSha.matches(Regex("[a-fA-F0-9]{64}")))) || bytes !in 1..256L * 1024 * 1024) null
            else Clip(s.getString("id"), name, url, sha, bytes, strings(s, "kinds"), strings(s, "concepts"), strings(s, "match_tags"), archiveUrl, archiveBytes, archiveSha)
        }
    }

    private fun catalog(ctx: Context, channel: String): List<Clip> {
        if (!enabled(ctx, channel)) return emptyList()
        catalogs[channel]?.let { return it }
        synchronized(locks.getOrPut("catalog-$channel") { Any() }) {
            catalogs[channel]?.let { return it }
            val cached = File(dir(ctx, channel), "manifest.json")
            if (cached.isFile) {
                val local = runCatching { parse(cached.readText()) }.getOrDefault(emptyList())
                if (local.isNotEmpty()) { catalogs[channel] = local; return local }
            }
            val now = System.currentTimeMillis()
            if (now - (attempted[channel] ?: 0L) >= RETRY_MS) {
                attempted[channel] = now
                val part = File(cached.parentFile, "manifest.part")
                runCatching {
                    fetch("$BASE/open-sounds-$channel-v1/manifest.json", part, MAX_META) { !enabled(ctx, channel) }
                    val text = part.readText()
                    val result = parse(text)
                    require(result.isNotEmpty())
                    check(part.renameTo(cached))
                    catalogs[channel] = result
                    return result
                }.onFailure { Log.i("OpenSoundBank", "$channel unavailable; fallback (${it.javaClass.simpleName})") }
                part.delete()
            }
            val result = runCatching { parse(cached.readText()) }.getOrDefault(emptyList())
            if (result.isNotEmpty()) catalogs[channel] = result
            return result
        }
    }

    private fun sha(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val block = ByteArray(64 * 1024)
            while (true) { val n = input.read(block); if (n < 0) break; md.update(block, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun clip(ctx: Context, channel: String, clip: Clip, cancelled: () -> Boolean): File? =
        synchronized(locks.getOrPut("$channel-${clip.id}") { Any() }) {
            if (!enabled(ctx, channel)) return@synchronized null
            val file = File(dir(ctx, channel), clip.file)
            if (file.isFile && file.length() == clip.bytes && sha(file).equals(clip.sha, true)) return@synchronized file
            val part = File(file.parentFile, clip.file + ".part")
            try {
                if (cancelled()) throw InterruptedException("cancelled")
                if (clip.archiveUrl.isNotEmpty()) {
                    unpack(ctx, channel, clip, cancelled)
                    return@synchronized file.takeIf { it.isFile && it.length() == clip.bytes && sha(it).equals(clip.sha, true) }
                }
                fetch(clip.url, part, clip.bytes) { cancelled() || !enabled(ctx, channel) }
                check(part.length() == clip.bytes && sha(part).equals(clip.sha, true)) { "recording hash mismatch" }
                check(part.renameTo(file))
                file
            } catch (e: InterruptedException) { if (enabled(ctx, channel)) throw e else null
            } catch (e: Exception) {
                Log.w("OpenSoundBank", "${clip.id} unavailable; fallback (${e.javaClass.simpleName})")
                null
            } finally { part.delete() }
        }

    private fun unpack(ctx: Context, channel: String, wanted: Clip, cancelled: () -> Boolean) {
        synchronized(locks.getOrPut(wanted.archiveUrl) { Any() }) {
            val selected = File(dir(ctx, channel), wanted.file)
            if (selected.isFile && selected.length() == wanted.bytes && sha(selected).equals(wanted.sha, true)) return
            val archive = File(ctx.cacheDir, "open-bank-$channel-${wanted.archiveSha.take(16)}.zip")
            val part = File(archive.path + ".part")
            try {
                if (!archive.isFile || archive.length() != wanted.archiveBytes || !sha(archive).equals(wanted.archiveSha, true)) {
                    fetch(wanted.archiveUrl, part, wanted.archiveBytes) { cancelled() || !enabled(ctx, channel) }
                    check(part.length() == wanted.archiveBytes && sha(part).equals(wanted.archiveSha, true))
                    check(part.renameTo(archive))
                }
                val expected = catalog(ctx, channel).associateBy { it.file }
                java.util.zip.ZipInputStream(archive.inputStream().buffered()).use { zip ->
                    while (true) {
                        if (cancelled() || !enabled(ctx, channel)) throw InterruptedException("cancelled")
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        check(File(entry.name).name == entry.name) { "unsafe archive path" }
                        val spec = expected[entry.name] ?: continue
                        val target = File(dir(ctx, channel), spec.file)
                        if (target.isFile && target.length() == spec.bytes && sha(target).equals(spec.sha, true)) continue
                        val temp = File(target.path + ".unpack")
                        try {
                            temp.outputStream().buffered().use { out ->
                                val block = ByteArray(64 * 1024); var size = 0L
                                while (true) {
                                    if (cancelled() || !enabled(ctx, channel)) throw InterruptedException("cancelled")
                                    val n = zip.read(block); if (n < 0) break
                                    size += n; check(size <= spec.bytes) { "archive entry too large" }; out.write(block, 0, n)
                                }
                            }
                            check(temp.length() == spec.bytes && sha(temp).equals(spec.sha, true)) { "archive recording hash mismatch" }
                            check(temp.renameTo(target))
                        } finally { temp.delete() }
                    }
                }
                archive.delete()
            } finally { part.delete() }
        }
    }

    private fun fetch(url: String, target: File, limit: Long, cancelled: () -> Boolean = { false }) {
        if (cancelled()) throw InterruptedException("cancelled")
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
        try {
            check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
            check(c.contentLengthLong <= limit) { "recording too large" }
            c.inputStream.buffered().use { input -> target.outputStream().buffered().use { output ->
                val block = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException("cancelled")
                    val n = input.read(block); if (n < 0) break
                    total += n; check(total <= limit) { "recording too large" }; output.write(block, 0, n)
                }
            } }
        } finally { c.disconnect() }
    }

    fun known(ctx: Context, kind: String): Boolean = catalog(ctx, "events").any { kind in it.kinds }

    fun events(ctx: Context, kind: String, cancelled: () -> Boolean = { false }): List<File> {
        val matching = catalog(ctx, "events").filter { kind in it.kinds }
        // Several takes, never an arbitrary similar event (cry of pain is not sobbing).
        return matching.take(3).mapNotNull { clip(ctx, "events", it, cancelled) }
    }

    fun scene(ctx: Context, prompt: String, salt: Int, cancelled: () -> Boolean = { false }): File? {
        val clips = catalog(ctx, "scenes")
        val matching = OpenBankMatch.scenes(prompt, clips.map { OpenBankMatch.Scene(it.id, it.concepts, it.tags) })
        if (matching.isEmpty()) return null
        val offset = Math.floorMod(salt, matching.size)
        for (i in matching.indices) {
            val id = matching[(i + offset) % matching.size]
            val entry = clips.firstOrNull { it.id == id } ?: continue
            clip(ctx, "scenes", entry, cancelled)?.let { return it }
        }
        return null
    }

    fun downloadAll(ctx: Context, channel: String, progress: (Int, Int, String) -> Unit): Int {
        if (!enabled(ctx, channel)) return 0
        val list = catalog(ctx, channel)
        check(list.isNotEmpty()) { "Список базы пока недоступен; повторите загрузку позже" }
        val ready = java.util.concurrent.atomic.AtomicInteger(0)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val queue = java.util.concurrent.LinkedBlockingQueue(list)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            val jobs = (1..6).map { pool.submit {
                while (enabled(ctx, channel)) {
                    val entry = queue.poll() ?: break
                    if (clip(ctx, channel, entry) { !enabled(ctx, channel) } != null) ready.incrementAndGet()
                    progress(done.incrementAndGet(), list.size, entry.file)
                }
            } }
            jobs.forEach { it.get() }
        } finally { pool.shutdown() }
        return ready.get()
    }

    const val LICENSES_URL = "$BASE/open-sounds-events-v1/CREDITS.md"
}
