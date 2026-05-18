package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
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
    private const val MAX_FILE_BYTES = 50 * 1024 * 1024 // 50 MB sanity cap

    @Volatile private var entries: Map<String, String> = emptyMap()
    @Volatile private var isLoaded = false

    // Match any letter-only word, including non-ASCII alphabets (Cyrillic, Greek, etc.).
    private val wordPattern: Pattern = Pattern.compile("\\p{L}+")

    fun load(context: Context) {
        if (isLoaded) return
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            entries = emptyMap()
            isLoaded = true
            return
        }
        try {
            val json = file.readText()
            entries = parseJsonToMap(json)
            Log.i(TAG, "Loaded ${entries.size} accent entries from disk")
        } catch (e: Exception) {
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

    fun apply(text: String): String {
        if (entries.isEmpty()) return text

        val matcher = wordPattern.matcher(text)
        val sb = StringBuffer()
        while (matcher.find()) {
            val original = matcher.group() ?: continue
            val replacement = entries[original.lowercase()] ?: continue
            val cased = applyCasing(original, replacement)
            // appendReplacement treats $ and \ specially — escape them.
            matcher.appendReplacement(sb, cased.replace("\\", "\\\\").replace("$", "\\$"))
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    /**
     * Imports a dictionary from a content:// URI selected by the user.
     * @return number of entries loaded (or -1 on failure).
     */
    fun importFromUri(context: Context, uri: Uri): Int {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes()
            } ?: return -1
            if (bytes.size > MAX_FILE_BYTES) {
                Log.w(TAG, "Refusing dictionary ${bytes.size} bytes — over ${MAX_FILE_BYTES} cap")
                return -1
            }
            val parsed = parseJsonToMap(String(bytes, Charsets.UTF_8))
            if (parsed.isEmpty()) return 0
            saveAndCache(context, parsed)
            parsed.size
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            -1
        }
    }

    fun clear(context: Context) {
        entries = emptyMap()
        isLoaded = true
        File(context.filesDir, FILE_NAME).delete()
    }

    private fun saveAndCache(context: Context, map: Map<String, String>) {
        entries = map
        isLoaded = true
        try {
            val json = JSONObject()
            for ((k, v) in map) json.put(k, v)
            File(context.filesDir, FILE_NAME).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
        }
    }

    private fun parseJsonToMap(json: String): Map<String, String> {
        val obj = JSONObject(json)
        val out = HashMap<String, String>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next() ?: continue
            val value = obj.optString(key, null) ?: continue
            if (key.isBlank() || value.isBlank()) continue
            // Store keys lowercased so lookups can be case-insensitive.
            out[key.lowercase()] = value
        }
        return out
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
