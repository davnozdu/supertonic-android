package com.brahmadeo.supertonic.tts.llm

import org.json.JSONArray
import org.json.JSONObject

/** Official DeepSeek API; credentials are supplied only by encrypted user settings. */
object DeepSeekApi {
    const val ENDPOINT = "https://api.deepseek.com"
    const val DEFAULT_MODEL = "deepseek-flash"
    fun models(response: JSONObject): List<String> {
        val data = response.getJSONArray("data")
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
            .distinct().sortedWith(compareBy<String> { it != DEFAULT_MODEL }.thenBy { it })
    }
    /** [effort]: reasoning depth with thinking (high / medium / low), null — the API default. */
    fun request(model: String, thinking: Boolean, system: String, prompt: String, tokens: Int, temperature: Double = 0.0,
                effort: String? = null): JSONObject {
        require(model.isNotBlank()) { "Выберите модель DeepSeek" }
        return JSONObject().put("model", model).put("stream", false).put("temperature", temperature)
            .put("max_tokens", tokens).put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
            .apply { if (thinking && effort in listOf("high", "medium", "low")) put("reasoning_effort", effort) }
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", prompt)))
    }
    fun content(response: JSONObject): String {
        val choice = response.getJSONArray("choices").getJSONObject(0)
        if (choice.optString("finish_reason") == "length") throw LlmProviders.CloudOutputLimitException()
        return choice.getJSONObject("message").getString("content")
    }
}
