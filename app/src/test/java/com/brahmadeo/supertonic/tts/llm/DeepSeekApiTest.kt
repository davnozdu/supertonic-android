package com.brahmadeo.supertonic.tts.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeepSeekApiTest {
    @Test fun catalogKeepsApiIdsAndPrioritizesFlash() {
        val response = JSONObject("""{"data":[{"id":"deepseek-v4-pro"},{"id":"deepseek-flash"},{"id":"deepseek-v4-pro"},{"id":""},{}]}""")
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), DeepSeekApi.models(response))
    }
    @Test fun requestUsesSelectedModelAndExplicitThinkingSwitch() {
        for (thinking in listOf(false, true)) {
            val request = DeepSeekApi.request("deepseek-v4-pro", thinking, "Return JSON", "Текст", 2400)
            assertEquals("deepseek-v4-pro", request.getString("model"))
            assertEquals(if (thinking) "enabled" else "disabled", request.getJSONObject("thinking").getString("type"))
            assertEquals("json_object", request.getJSONObject("response_format").getString("type"))
            assertEquals(2400, request.getInt("max_tokens"))
            assertEquals("Текст", request.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertFalse(request.getBoolean("stream"))
        }
    }
    @Test fun returnsContentWithoutReasoning() {
        assertEquals("{\"texts\":[\"Те́кст\"]}", DeepSeekApi.content(JSONObject("""{"choices":[{"finish_reason":"stop","message":{"reasoning_content":"private reasoning","content":"{\"texts\":[\"Те́кст\"]}"}}]}""")))
    }
    @Test fun truncatedAnswerIsRejected() {
        try {
            DeepSeekApi.content(JSONObject("""{"choices":[{"finish_reason":"length","message":{"content":"{\"texts\":[]}"}}]}"""))
            throw AssertionError("A truncated JSON response must be rejected")
        } catch (_: LlmProviders.CloudOutputLimitException) { }
    }
}
