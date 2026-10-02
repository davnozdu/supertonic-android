package com.brahmadeo.supertonic.tts.llm

import android.content.Context
import org.json.JSONArray

/** Test annotations only: model choices themselves always come from the provider API. */
object LlmModelRecommendations {
    data class Recommendation(val priority: Int, val label: String)
    fun load(context: Context): Map<String, Recommendation> = runCatching {
        val array = JSONArray(context.assets.open("llm_recommendations.json").bufferedReader().use { it.readText() })
        (0 until array.length()).associate { i ->
            val item = array.getJSONObject(i)
            (item.getString("provider") + "/" + item.getString("model")) to Recommendation(item.getInt("priority"), item.getString("label"))
        }
    }.getOrDefault(emptyMap())
}
