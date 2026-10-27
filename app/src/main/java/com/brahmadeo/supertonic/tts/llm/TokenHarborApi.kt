package com.brahmadeo.supertonic.tts.llm

/** Token Harbor gateway (OpenAI-compatible, the same request as [DeepSeekApi]); credentials only from encrypted
 * settings. A free account has 60 requests a minute (1 800 an hour), far above what MyTTS sends, so nothing is spaced
 * here; a 429 pauses the provider like any other. Its DeepSeek models think unless told not to: the request always
 * carries «thinking: disabled» (without it the whole token limit went to reasoning and the answer was empty). */
object TokenHarborApi {
    const val ENDPOINT = "https://tokenharbor.ai/v1"
    const val DEFAULT_MODEL = "deepseek-v4.1-flash:free"

    fun free(model: String) = model.endsWith(":free")
}
