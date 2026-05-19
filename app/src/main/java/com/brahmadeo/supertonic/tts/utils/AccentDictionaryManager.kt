package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL
import java.util.regex.Pattern

/**
 * Bulk imported accent / pronunciation dictionary.
 *
 * Distinct from [LexiconManager]:
 * - LexiconManager: small, hand-curated rules (whole-word or regex).
 *   Iterates every rule against every text. Fine for <100 entries.
 * - AccentDictionaryManager: large word-indexed map (tens of thousands of
 *   entries). One HashMap lookup per word, so cost scales with text
 *   length, not with dictionary size.
 *
 * User lexicon always wins (applied first; if it rewrites a word, the
 * accent dictionary won't see the original form).
 *
 * Expected JSON shape — a flat object, key = original word, value =
 * pronunciation with stress marks (Unicode U+0301 after the stressed vowel):
 *
 * ```json
 * { "замок": "замо́к", "Москва": "Москва́" }
 * ```
 *
 * Lookups are case-insensitive by default; original casing is restored on
 * replacement so "Замок" -> "Замо́к".
 */
object AccentDictionaryManager {
    private const val TAG = "AccentDict"
    private const val FILE_NAME = "accent_dictionary.json"
    // 250 MB cap — the full Russian dictionary is ~165 MB. The HashMap that
    // backs it takes ~500 MB of heap; that's fine on phones with ≥ 4 GB of
    // RAM but would OOM a budget device. Users who want the smaller dict can
    // still import russian_accents.json (36 MB) via the file picker.
    private const val MAX_FILE_BYTES = 250L * 1024 * 1024

    data class PrebuiltDict(
        val id: String,
        val displayName: String,
        val subtitle: String,
        val sizeBytes: Long,
        val entries: Int,
        val url: String
    )

    /**
     * Pre-built dictionaries published in the davnozdu/supertonic-dictionaries
     * GitHub repository (separate from the app for independent versioning).
     * Keyed by language code; each language can offer several sizes so the
     * user picks based on phone RAM.
     */
    private val PREBUILT_DICTS: Map<String, List<PrebuiltDict>> = mapOf(
        "ru" to listOf(
            PrebuiltDict(
                id = "ru-full",
                displayName = "Full",
                subtitle = "165 MB · 3.26M entries · ~500 MB RAM · names, homographs, ё",
                sizeBytes = 165L * 1024 * 1024,
                entries = 3_263_003,
                url = "https://github.com/davnozdu/supertonic-dictionaries/releases/download/russian-v1.0/russian_accents_full.json"
            ),
            PrebuiltDict(
                id = "ru-standard",
                displayName = "Standard",
                subtitle = "36 MB · 962K entries · ~150 MB RAM · words ≤ 9 chars, no homographs",
                sizeBytes = 36L * 1024 * 1024,
                entries = 961_968,
                url = "https://github.com/davnozdu/supertonic-dictionaries/releases/download/russian-v1.0/russian_accents.json"
            ),
            PrebuiltDict(
                id = "ru-compact",
                displayName = "Compact",
                subtitle = "21 MB · 615K entries · ~85 MB RAM · words ≤ 8 chars",
                sizeBytes = 21L * 1024 * 1024,
                entries = 615_365,
                url = "https://github.com/davnozdu/supertonic-dictionaries/releases/download/russian-v1.0/russian_accents_compact.json"
            )
        )
    )

    fun hasPrebuiltFor(lang: String): Boolean = PREBUILT_DICTS.containsKey(lang.lowercase().substringBefore('-'))

    fun prebuiltOptionsFor(lang: String): List<PrebuiltDict> {
        return PREBUILT_DICTS[lang.lowercase().substringBefore('-')] ?: emptyList()
    }

    data class Metadata(val source: String, val entries: Int, val loadedAtMs: Long, val sizeBytes: Long)

    @Volatile private var entries: Map<String, String> = emptyMap()
    @Volatile private var isLoaded = false
    @Volatile private var fallbackEnabled = false

    // Match a word as "Unicode letters + any combining marks attached to them".
    // The \p{M} part is the fix for the double-stress bug: if a previous step
    // (user Lexicon) already inserted U+0301 into "Москва" -> "Москва́", a
    // bare \p{L}+ would match only "Москва", we'd replace it from the
    // dictionary with another stressed form, and appendTail would re-emit
    // the orphan U+0301 — producing "Москва́́" with two combining accents.
    // Including \p{M} makes the matcher consume the existing diacritic, so
    // its lowercased form misses the (unstressed-key) dictionary and the
    // word is preserved as the user marked it.
    private val wordPattern: Pattern = Pattern.compile("[\\p{L}\\p{M}]+")
    private const val META_PREFS = "AccentDictMeta"
    private const val META_KEY_SOURCE = "source"
    private const val META_KEY_ENTRIES = "entries"
    private const val META_KEY_LOADED_AT = "loaded_at"
    private const val META_KEY_SIZE_BYTES = "size_bytes"
    private const val FALLBACK_PREFS_KEY = "accent_fallback_enabled"

    // Russian vowels (lowercase). Used both by the fallback rule and to count
    // syllables when deciding whether the fallback should kick in at all —
    // single-syllable words like "и", "за", "не" must not be touched.
    private const val RU_VOWELS = "аеёиоуыэюя"
    private val ACUTE = '́'

    fun load(context: Context) {
        if (isLoaded) return
        fallbackEnabled = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .getBoolean(FALLBACK_PREFS_KEY, false)
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            entries = emptyMap()
            isLoaded = true
            return
        }
        try {
            // Streaming parser — never builds the whole tree in memory.
            // Critical for the 165 MB Full dictionary, which OOMs JSONObject.
            FileInputStream(file).use { fis ->
                entries = parseJsonStream(fis, file.length())
            }
            Log.i(TAG, "Loaded ${entries.size} accent entries from disk")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load accent dictionary", e)
            entries = emptyMap()
        }
        isLoaded = true
    }

    fun reload(context: Context) {
        isLoaded = false
        load(context)
    }

    fun size(): Int = entries.size

    fun isReady(): Boolean = entries.isNotEmpty()

    fun apply(text: String, lang: String = ""): String {
        // No work to do if both the dictionary is empty and fallback is off.
        if (entries.isEmpty() && !fallbackEnabled) return text

        val applyFallback = fallbackEnabled && lang.startsWith("ru")
        val matcher = wordPattern.matcher(text)
        val sb = StringBuffer()
        while (matcher.find()) {
            val original = matcher.group() ?: continue
            val dictHit = entries[original.lowercase()]
            val cased = when {
                dictHit != null -> applyCasing(original, dictHit)
                applyFallback -> fallbackLastVowel(original) ?: continue
                else -> continue
            }
            // appendReplacement treats $ and \ specially — escape them.
            matcher.appendReplacement(sb, cased.replace("\\", "\\\\").replace("$", "\\$"))
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    /**
     * Best-effort stress for a word that didn't show up in the dictionary.
     *
     * Default heuristic is **paroxytone** — stress on the *penultimate* vowel —
     * which beats "last vowel" on average for Russian: in a Zipf-weighted
     * frequency corpus, words with stress on the second-to-last syllable
     * outnumber oxytones roughly 2:1, and the paroxytone choice is right far
     * more often for nouns, adjectives and most verb forms.
     *
     * Some suffixes are reliably non-paroxytone, so we special-case the most
     * common ones:
     * - `-ция` / `-сия` (станция, акция, операция, демонстрация): stress on
     *   the vowel right before the suffix (опера́ция, демонстра́ция).
     * - `-ение` / `-ание` / `-ование` (учение, образование, требование):
     *   stress on the vowel right before -ние (уче́ние, образова́ние).
     * - `-ист` (лингвист, программист, журналист): stress on the suffix's `и`.
     *
     * Guard rails: skip words that already have stress, single-vowel words,
     * and words with no vowels at all.
     */
    private fun fallbackLastVowel(word: String): String? {
        if (word.any { it == ACUTE }) return null

        val lower = word.lowercase()

        // Collect vowel positions once — used by every branch below.
        val vowelPositions = ArrayList<Int>(word.length / 2)
        for ((i, ch) in lower.withIndex()) {
            if (ch in RU_VOWELS) vowelPositions.add(i)
        }
        if (vowelPositions.size < 2) return null

        // Suffix-based overrides for cases where paroxytone is almost always wrong.
        val targetIdx: Int = when {
            // -ция / -сия → vowel immediately before the "-ция" tail
            lower.endsWith("ция") || lower.endsWith("сия") -> {
                val cutoff = word.length - 3
                vowelPositions.lastOrNull { it < cutoff } ?: return null
            }
            // -ние with a vowel-before-"н" pattern (учение, образование, требование)
            lower.endsWith("ние") -> {
                val cutoff = word.length - 3
                vowelPositions.lastOrNull { it < cutoff } ?: return null
            }
            // -ист → stress the suffix's "и"
            lower.endsWith("ист") -> word.length - 3
            // Default: paroxytone (penultimate vowel)
            else -> vowelPositions[vowelPositions.size - 2]
        }

        return word.substring(0, targetIdx + 1) + ACUTE + word.substring(targetIdx + 1)
    }

    fun setFallbackEnabled(context: Context, enabled: Boolean) {
        fallbackEnabled = enabled
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(FALLBACK_PREFS_KEY, enabled)
            .apply()
    }

    fun isFallbackEnabled(): Boolean = fallbackEnabled

    fun getMetadata(context: Context): Metadata? {
        val prefs = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        val source = prefs.getString(META_KEY_SOURCE, null) ?: return null
        return Metadata(
            source = source,
            entries = prefs.getInt(META_KEY_ENTRIES, 0),
            loadedAtMs = prefs.getLong(META_KEY_LOADED_AT, 0L),
            sizeBytes = prefs.getLong(META_KEY_SIZE_BYTES, 0L)
        )
    }

    private fun writeMetadata(context: Context, source: String, entries: Int, sizeBytes: Long) {
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit()
            .putString(META_KEY_SOURCE, source)
            .putInt(META_KEY_ENTRIES, entries)
            .putLong(META_KEY_LOADED_AT, System.currentTimeMillis())
            .putLong(META_KEY_SIZE_BYTES, sizeBytes)
            .apply()
    }

    private fun clearMetadata(context: Context) {
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).edit()
            .remove(META_KEY_SOURCE)
            .remove(META_KEY_ENTRIES)
            .remove(META_KEY_LOADED_AT)
            .remove(META_KEY_SIZE_BYTES)
            .apply()
    }

    /**
     * Imports a dictionary from a content:// URI selected by the user.
     *
     * Two-pass: stream the URI into a tmp file (we don't know its size from
     * content://, so this enforces the cap), then stream-parse the file.
     * Never materialises the whole JSON in memory.
     *
     * @return number of entries loaded (or a negative ImportError code on failure).
     */
    fun importFromUri(context: Context, uri: Uri): Int {
        val tmp = File(context.cacheDir, "accent_import.tmp")
        return try {
            val written = context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out ->
                    copyWithCap(input, out, MAX_FILE_BYTES)
                }
            } ?: return ERR_IO
            if (written < 0) {
                tmp.delete()
                return ERR_TOO_LARGE
            }
            val parsed = FileInputStream(tmp).use { parseJsonStream(it, tmp.length()) }
            if (parsed.isEmpty()) {
                tmp.delete()
                return 0
            }
            installFromTmp(context, tmp, parsed, "Imported from file")
            parsed.size
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "Import OOM", oom)
            tmp.delete()
            entries = emptyMap()
            ERR_OOM
        } catch (e: Throwable) {
            Log.e(TAG, "Import failed", e)
            tmp.delete()
            ERR_PARSE
        }
    }

    fun clear(context: Context) {
        entries = emptyMap()
        isLoaded = true
        File(context.filesDir, FILE_NAME).delete()
        clearMetadata(context)
    }

    // Negative return codes from importFromUri / downloadPrebuilt so the UI
    // can show a specific reason instead of a generic "failed" toast.
    const val ERR_IO = -1
    const val ERR_OOM = -2
    const val ERR_PARSE = -3
    const val ERR_TOO_LARGE = -4
    const val ERR_NETWORK = -5
    const val ERR_EMPTY = 0

    /**
     * Downloads and installs the pre-built dictionary for [lang] (currently only "ru").
     * Streams bytes to a temp file with progress callbacks so the UI can show a bar
     * instead of freezing for ~10 s on a 36 MB file. Reports byte counts; the caller
     * decides whether to translate them into a percentage.
     *
     * Returns the number of entries loaded, or a negative ERR_* code on failure.
     */
    fun downloadPrebuilt(
        context: Context,
        urlStr: String,
        sourceName: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Int {
        val tmp = File(context.cacheDir, "accent_download.tmp")
        try {
            val conn = URL(urlStr).openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            val total = conn.contentLengthLong.let { if (it > 0) it else -1L }
            conn.getInputStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var soFar = 0L
                    var lastReport = 0L
                    while (true) {
                        read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        soFar += read
                        // Throttle UI updates to every 200 KB to keep logcat sane.
                        if (soFar - lastReport >= 200 * 1024 || total > 0 && soFar >= total) {
                            onProgress(soFar, total)
                            lastReport = soFar
                        }
                    }
                    onProgress(soFar, soFar)
                }
            }
            if (tmp.length() > MAX_FILE_BYTES) {
                Log.w(TAG, "Downloaded dict ${tmp.length()} bytes exceeds cap")
                tmp.delete()
                return ERR_TOO_LARGE
            }
            onProgress(tmp.length(), tmp.length()) // signal "parsing now"
            // Stream-parse straight off disk — no readText, no JSONObject.
            val parsed = FileInputStream(tmp).use { parseJsonStream(it, tmp.length()) }
            if (parsed.isEmpty()) {
                tmp.delete()
                return ERR_EMPTY
            }
            installFromTmp(context, tmp, parsed, sourceName)
            return parsed.size
        } catch (oom: OutOfMemoryError) {
            // Parsing the full 165 MB dict allocates ~390 MB for the HashMap
            // alone. With largeHeap=true that fits on 4 GB+ phones, but on
            // smaller devices it OOMs. Tell the user explicitly so they pick a
            // smaller dict instead of staring at a silent "failed" toast.
            Log.e(TAG, "downloadPrebuilt OOM from $urlStr", oom)
            tmp.delete()
            entries = emptyMap()
            return ERR_OOM
        } catch (e: java.io.IOException) {
            Log.e(TAG, "downloadPrebuilt network failure from $urlStr", e)
            tmp.delete()
            return ERR_NETWORK
        } catch (e: Throwable) {
            Log.e(TAG, "downloadPrebuilt failed from $urlStr", e)
            tmp.delete()
            return ERR_PARSE
        }
    }

    /**
     * Move the validated tmp file into place and swap the in-memory map.
     *
     * The big win over the old saveAndCache: we never re-serialise the map
     * via JSONObject.toString() (which would allocate a second 200+ MB
     * String on top of the already-loaded HashMap and OOM most phones).
     * The tmp file is *already* valid JSON — we just rename it.
     */
    private fun installFromTmp(
        context: Context,
        tmp: File,
        parsed: Map<String, String>,
        sourceName: String
    ) {
        val target = File(context.filesDir, FILE_NAME)
        if (target.exists()) target.delete()
        val size = tmp.length()
        if (!tmp.renameTo(target)) {
            // Cross-device fallback (cacheDir and filesDir can be different
            // mounts on some OEMs); copy then drop the original.
            tmp.inputStream().use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            tmp.delete()
        }
        entries = parsed
        isLoaded = true
        writeMetadata(context, sourceName, parsed.size, size)
    }

    /**
     * Stream-parse a `{"word": "wórd", ...}` JSON object directly from an
     * InputStream. Uses constant memory (one key/value pair at a time) so the
     * full 165 MB dict only allocates the final HashMap, not the parse tree.
     */
    private fun parseJsonStream(input: InputStream, knownSize: Long): HashMap<String, String> {
        // Pre-size the HashMap roughly so we don't pay a dozen resizes during
        // load. ~10 bytes per entry on disk for Cyrillic JSON is a decent guess.
        val estimatedEntries = if (knownSize > 0) (knownSize / 10).toInt().coerceAtLeast(64) else 64
        val out = HashMap<String, String>(estimatedEntries)
        JsonReader(InputStreamReader(BufferedInputStream(input), Charsets.UTF_8)).use { reader ->
            reader.isLenient = true
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                val value = reader.nextString()
                if (key.isNotBlank() && value.isNotBlank()) {
                    out[key.lowercase()] = value
                }
            }
            reader.endObject()
        }
        return out
    }

    /**
     * Copy [input] to [output] up to [cap] bytes. Returns bytes written,
     * or -1 if the source exceeded the cap.
     */
    private fun copyWithCap(input: InputStream, output: FileOutputStream, cap: Long): Long {
        val buf = ByteArray(64 * 1024)
        var soFar = 0L
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            soFar += read
            if (soFar > cap) return -1
            output.write(buf, 0, read)
        }
        return soFar
    }

    /**
     * Reapply the casing pattern of [original] to [replacement], so a
     * lowercase-keyed entry can still respond to "Москва" by returning
     * "Москва́" instead of "москва́".
     */
    private fun applyCasing(original: String, replacement: String): String {
        val origLower = original.lowercase()
        if (original == origLower) return replacement
        if (original == original.uppercase()) return replacement.uppercase()
        if (original.length > 1 &&
            original[0].isUpperCase() &&
            original.substring(1) == original.substring(1).lowercase()
        ) {
            // Title case: capitalize the first letter of the replacement.
            return replacement.replaceFirstChar { it.uppercase() }
        }
        return replacement
    }
}
