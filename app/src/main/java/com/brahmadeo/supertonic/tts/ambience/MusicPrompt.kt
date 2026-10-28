package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import com.brahmadeo.supertonic.tts.llm.LlmProviders
import org.json.JSONObject

/** The English prompt for the music model from a description in Russian or English («Своя музыка»). The model only
 * understands English: a Russian description is translated by the configured cloud first, then by the local Gemma 4. */
object MusicPrompt {
    private const val INSTRUCTION = "Translate a description of background music into an English prompt for a music generator. " +
        "8–30 words: instruments, mood, style, tempo in BPM, end with \"no vocals\". Keep the meaning, add nothing else. " +
        "Answer only JSON {\"english\":\"...\"}."
    private val schema = JSONObject("""{"type":"object","properties":{"english":{"type":"string"}},"required":["english"],"additionalProperties":false}""")

    /** The same for a sound of a place or of an event: no music wording, no «no vocals». */
    private const val SOUND_INSTRUCTION = "Translate a description of a sound effect into an English prompt for a sound " +
        "generator. 6-20 words: what sounds, from what material, how long, how it changes. Keep the meaning, add nothing " +
        "else, no music. Answer only JSON {\"english\":\"...\"}."

    fun cyrillic(text: String) = text.any { it in 'Ѐ'..'ӿ' }

    /** [text] itself when it is English; otherwise its translation (cloud → Gemma 4), or an error asking for English. */
    fun english(context: Context, text: String): String = english(context, text, INSTRUCTION)

    /** The same for the description of a sound (a place's background, an event): translated as a sound, not as music. */
    fun englishSound(context: Context, text: String): String = english(context, text, SOUND_INSTRUCTION)

    private fun english(context: Context, text: String, instruction: String): String {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "Опишите музыку" }
        if (!cyrillic(clean)) return clean
        val cloud = runCatching {
            val source = com.brahmadeo.supertonic.tts.books.prepare.BookPreparation.source(context).withThinking(false)
            parse(LlmProviders.cloudRequest(source.config, clean, instruction, schema, source.gemini, tokens = 400, deadlineMs = 30_000L,
                provider = source.provider, temperature = 0.2))
        }.getOrNull()
        if (cloud != null) return cloud
        val local = runCatching {
            parse(LlmProviders.localRaw(context, com.brahmadeo.supertonic.tts.llm.LlmSettings.load(context), instruction, clean))
        }.getOrNull()
        return local ?: error("Не удалось перевести описание: облако и Gemma 4 недоступны. Напишите описание по-английски")
    }

    private fun parse(raw: String): String? {
        val text = raw.trim()
        val json = runCatching { JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)).optString("english") }.getOrNull()
        return (json ?: text).trim().takeIf { it.length in 8..400 && !cyrillic(it) }
    }
}
