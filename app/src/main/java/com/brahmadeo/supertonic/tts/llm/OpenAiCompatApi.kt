package com.brahmadeo.supertonic.tts.llm

import org.json.JSONArray
import org.json.JSONObject

/** Свой сервер по OpenAI-совместимому API (llama.cpp, vLLM, LM Studio, OpenRouter, шлюзы провайдеров).
 * Адрес задаёт пользователь: принимаем и «…/v1», и адрес без него, и полный путь до /chat/completions. */
object OpenAiCompatApi {
    /** Адрес запроса: [suffix] = "/chat/completions" или "/models". */
    fun url(base: String, suffix: String): String {
        var b = base.trim().trimEnd('/')
        require(b.isNotEmpty()) { "Введите адрес сервера" }
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "https://$b"
        // Полный путь уже указан — не добавляем второй раз.
        if (b.endsWith("/chat/completions")) return b.removeSuffix("/chat/completions") + suffix
        if (b.endsWith("/v1") || b.endsWith("/openai/v1") || b.contains("/v1/")) return b + suffix
        return b + "/v1" + suffix
    }

    fun models(response: JSONObject): List<String> {
        // OpenAI отдаёт data[].id; некоторые серверы — models[].name / models[].id.
        val data = response.optJSONArray("data") ?: response.optJSONArray("models") ?: JSONArray()
        return (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            (item.optString("id").ifBlank { item.optString("name") }).takeIf(String::isNotBlank)
        }.distinct().sorted()
    }

    /** Тело запроса. [tokenField]: старые серверы понимают max_tokens, новые модели OpenAI — только
     * max_completion_tokens и отвечают 400 на незнакомое поле. Поле «размышления» шлём только по просьбе:
     * незнакомые поля часть серверов отвергает. */
    fun request(model: String, system: String, prompt: String, tokens: Int, temperature: Double, thinking: Boolean,
                tokenField: String = "max_tokens"): JSONObject {
        require(model.isNotBlank()) { "Выберите модель своего сервера" }
        return JSONObject().put("model", model).put("stream", false).put("temperature", temperature)
            .put(tokenField, tokens)
            .apply { if (thinking) put("reasoning_effort", "medium") }
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", prompt)))
    }
}
