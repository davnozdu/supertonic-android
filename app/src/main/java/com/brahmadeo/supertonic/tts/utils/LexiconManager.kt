package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Matcher
import java.util.regex.Pattern

data class LexiconItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    var term: String,
    var replacement: String,
    var ignoreCase: Boolean = true,
    var isRegex: Boolean = false
)

object LexiconManager {
    private const val FILE_NAME = "user_lexicon.json"
    private var cachedRules: List<LexiconItem> = emptyList()
    @Volatile private var isLoaded = false

    fun load(context: Context): List<LexiconItem> {
        // Always reload from file if not loaded or if requested, 
        // but for performance we cache.
        if (isLoaded) return cachedRules
        
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            cachedRules = emptyList()
            isLoaded = true
            return cachedRules
        }

        val items = mutableListOf<LexiconItem>()
        try {
            val jsonString = file.readText()
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                items.add(LexiconItem(
                    id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                    term = obj.getString("term"),
                    replacement = obj.getString("replacement"),
                    ignoreCase = obj.optBoolean("ignoreCase", true),
                    isRegex = obj.optBoolean("isRegex", false)
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        cachedRules = items
        isLoaded = true
        return items
    }

    fun save(context: Context, items: List<LexiconItem>) {
        cachedRules = items.toList() // Update cache immediately
        isLoaded = true
        
        try {
            val jsonArray = JSONArray()
            for (item in items) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("term", item.term)
                obj.put("replacement", item.replacement)
                obj.put("ignoreCase", item.ignoreCase)
                obj.put("isRegex", item.isRegex)
                jsonArray.put(obj)
            }
            
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun apply(text: String): String {
        // If not loaded, we can't apply rules safely without context to load them.
        // Consumers must ensure load(context) is called at app start.
        if (cachedRules.isEmpty()) return text
        
        var processed = text
        
        for (item in cachedRules) {
            if (item.term.isBlank()) continue

            // UNICODE_CHARACTER_CLASS is critical for non-Latin scripts: without
            // it Java's `\b` (and `\w`) only match ASCII word characters, so a
            // whole-word rule like `\bзамок\b` would silently fail to match
            // inside Cyrillic text. UNICODE_CASE makes case-insensitive
            // matching fold Russian capitals as well.
            var flags = Pattern.UNICODE_CHARACTER_CLASS
            if (item.ignoreCase) {
                flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            }

            try {
                val pattern = if (item.isRegex) {
                    Pattern.compile(item.term, flags)
                } else {
                    // Whole word matching with quoting
                    Pattern.compile("\\b${Pattern.quote(item.term)}\\b", flags)
                }
                // Escape $ and \ in the replacement so user input doesn't get
                // interpreted as Matcher backreferences.
                val safeReplacement = Matcher.quoteReplacement(item.replacement)
                processed = pattern.matcher(processed).replaceAll(safeReplacement)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return processed
    }
    
    // Force reload (useful when returning from LexiconActivity)
    fun reload(context: Context) {
        isLoaded = false
        load(context)
    }
}
