package com.brahmadeo.supertonic.tts.llm

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.brahmadeo.supertonic.tts.utils.BinaryAccentDictionary
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The full Russian accent dictionary (3.26 M forms, memory-mapped .sacc, ~10-20 MB RAM) used ONLY as a
 * tie-breaker of the stress check: when the LLM judge answers the two option orders differently, a word that is
 * not a homograph takes the variant this dictionary has. It is deliberately kept apart from the user dictionary
 * of AccentDictionaryManager: applied to every word it would replace correct Silero marks with its colloquial
 * variants (зво́нит, до́говор, ща́вель). On the test runs the tie-break fixed ~31 marks and spoiled ~8.
 * Downloaded once in the background, on an unmetered network only, size and SHA-256 verified. */
object StressJudgeDictionary {
    private const val TAG = "StressJudgeDict"
    private const val URL_SACC = "https://github.com/davnozdu/supertonic-dictionaries/releases/download/russian-v1.1/russian_accents_full.sacc"
    private const val SIZE = 179_200_764L
    private const val SHA256 = "a1ac32606e99f6d8ab8e8ce796b0005b0693908555460656976c54e171658b27"
    private const val VOWELS = "аеёиоуыэюя"

    @Volatile private var dictionary: BinaryAccentDictionary? = null
    private val downloading = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "StressJudgeDict").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    private fun file(ctx: Context) = File(ctx.noBackupFilesDir, "stress-judge/russian_accents_full.sacc")
    private fun verified(ctx: Context) = File(file(ctx).parentFile, "verified-$SHA256")

    /** Opens the verified dictionary, or starts the one-time background download. Never blocks. */
    fun ensure(ctx: Context): Boolean {
        if (dictionary != null) return true
        val f = file(ctx)
        if (f.isFile && f.length() == SIZE && verified(ctx).isFile) {
            synchronized(this) { if (dictionary == null) dictionary = BinaryAccentDictionary.open(f) }
            return dictionary != null
        }
        val metered = runCatching { (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered }.getOrDefault(true)
        if (!metered && downloading.compareAndSet(false, true)) worker.execute {
            try { download(ctx.applicationContext) } catch (e: Exception) {
                Log.w(TAG, "Download failed: ${e.javaClass.simpleName}; retried later")
            } finally { downloading.set(false) }
        }
        return false
    }

    private fun download(ctx: Context) {
        val target = file(ctx); target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        val connection = URL(URL_SACC).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000; connection.readTimeout = 60_000
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            require(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { input -> part.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    total += n; require(total <= SIZE) { "too large" }
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                }
            } }
        } finally { connection.disconnect() }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (part.length() != SIZE || sha != SHA256) { part.delete(); error("size/sha mismatch") }
        check(part.renameTo(target)) { "rename failed" }
        verified(ctx).writeText(SHA256)
        Log.i(TAG, "Dictionary ready: ${target.length()} bytes")
    }

    /** Stressed vowel ordinal of [word] in the dictionary, or null when unknown or not loaded. */
    fun ordinal(word: String): Int? {
        val value = dictionary?.lookup(word.lowercase().replace("́", "").toByteArray(Charsets.UTF_8)) ?: return null
        val marked = Regex("\\+([аеёиоуыэюяАЕЁИОУЫЭЮЯ])").replace(value) { it.groupValues[1] + "́" }
        val mark = marked.indexOf('́')
        if (mark <= 0 || marked.indexOf('́', mark + 1) >= 0) return null
        return marked.substring(0, mark).lowercase().count { it in VOWELS }
    }
}
