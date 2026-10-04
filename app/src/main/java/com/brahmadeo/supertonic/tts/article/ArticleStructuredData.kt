package com.brahmadeo.supertonic.tts.article

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document

object ArticleStructuredData {
    fun body(doc: Document): String? {
        var best: String? = null
        val pending = java.util.ArrayDeque<Any>()
        doc.select("script[type=application/ld+json]").forEach { script ->
            runCatching { JSONTokener(script.data()).nextValue() }.getOrNull()?.let { pending.add(it) }
        }
        var count = 0
        while (pending.isNotEmpty() && ++count < 10_000) {
            when (val item = pending.removeFirst()) {
                is JSONObject -> {
                    val type = item.opt("@type")
                    val types = when (type) {
                        is String -> listOf(type)
                        is JSONArray -> (0 until type.length()).map { type.optString(it) }
                        else -> emptyList()
                    }
                    if (types.any { it.substringAfterLast('/') in setOf("Article", "NewsArticle", "BlogPosting", "TechArticle", "ScholarlyArticle", "Report") }) {
                        val text = (item.opt("articleBody") as? String)?.trim()
                        if (text != null && text.length >= 80 && text.length > (best?.length ?: 0)) best = text
                    }
                    item.keys().forEach { key -> item.opt(key)?.let { value -> if (value is JSONObject || value is JSONArray) pending.add(value) } }
                }
                is JSONArray -> (0 until item.length()).forEach { i -> item.opt(i)?.let { if (it is JSONObject || it is JSONArray) pending.add(it) } }
            }
        }
        return best
    }
}
