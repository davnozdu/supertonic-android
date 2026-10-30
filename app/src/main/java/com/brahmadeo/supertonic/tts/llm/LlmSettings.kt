package com.brahmadeo.supertonic.tts.llm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class LlmMode(val title: String) {
    OFF("Выключено"), AUTO("Авто: облако → Gemma 4"), OLLAMA("Ollama Cloud"),
    GEMINI("Gemini"), DEEPSEEK("DeepSeek"), APINEX("APInex"), TOKENHARBOR("TokenHarbor"),
    CUSTOM("Свой сервер (OpenAI-совместимый)"), LOCAL("Автономно: Gemma 4")
}

data class LlmConfig(
    val mode: LlmMode = LlmMode.OFF,
    val ollamaEndpoint: String = "https://ollama.com",
    val ollamaModel: String = "",
    val geminiModel: String = "",
    val ollamaKey: String = "",
    val geminiKey: String = "",
    val preferGemini: Boolean = false,
    val deepseekModel: String = DeepSeekApi.DEFAULT_MODEL,
    val deepseekKey: String = "",
    val deepseekThinking: Boolean = false,
    val preferDeepseek: Boolean = false,
    val apinexModel: String = ApinexApi.DEFAULT_MODEL,
    val apinexKey: String = "",
    val apinexThinking: Boolean = false,
    val preferApinex: Boolean = false,
    val tokenharborModel: String = TokenHarborApi.DEFAULT_MODEL,
    val tokenharborKey: String = "",
    val tokenharborThinking: Boolean = false,
    val preferTokenharbor: Boolean = false,
    /** Свой OpenAI-совместимый сервер: адрес вида https://host/v1, ключ (может быть пустым) и модель. */
    val customEndpoint: String = "",
    val customKey: String = "",
    val customModel: String = "",
    val customThinking: Boolean = false,
    val preferCustom: Boolean = false,
    /** Очередь облачных серверов: основной, резерв 1, резерв 2. Пустые пункты пропускаются. */
    val priority: List<String> = emptyList(),
    val ollamaThinking: Boolean = false,
    val geminiThinking: Boolean = false,
    val localThinking: Boolean = false,
    val gpu: Boolean = true,
    val idleSeconds: Int = 120,
    val punctuation: Boolean = true,
    val stress: Boolean = true,
    val aheadChars: Int = 16000,
    val restoreYo: Boolean = true,
    val multiVoice: Boolean = false,
    /** First-person narration: the narrator's own lines («— сказал я») are read by the author's voice. */
    val firstPerson: Boolean = false
)

/** Все облачные серверы, которые умеет приложение, с названиями для настроек. */
val CLOUD_TITLES: Map<String, String> = linkedMapOf(
    "ollama" to "Ollama Cloud", "gemini" to "Gemini", "deepseek" to "DeepSeek",
    "apinex" to "APInex", "tokenharbor" to "TokenHarbor", "custom" to "Свой сервер"
)

/** Cloud providers in the order they are tried: the chosen one (manual mode), then the queue from the settings
 * (основной → резерв 1 → резерв 2), then every other configured server. Empty slots and servers without a model
 * or a key are skipped by the caller, so the chain runs down to Gemma 4 or the offline dictionaries. */
fun LlmConfig.cloudOrder(): List<String> {
    if (mode == LlmMode.OFF || mode == LlmMode.LOCAL) return emptyList()
    val chosen = when (mode) {
        LlmMode.OLLAMA -> "ollama"
        LlmMode.GEMINI -> "gemini"
        LlmMode.DEEPSEEK -> "deepseek"
        LlmMode.APINEX -> "apinex"
        LlmMode.TOKENHARBOR -> "tokenharbor"
        LlmMode.CUSTOM -> "custom"
        else -> null
    }
    val queue = priority.map { it.trim() }.filter { it.isNotEmpty() && CLOUD_TITLES.containsKey(it) }
    val configured = CLOUD_TITLES.keys.filter { cloudReady(it) }
    return (listOfNotNull(chosen) + queue + configured).distinct()
}

/** A cloud provider with what it needs (model, key). Network is checked separately. */
fun LlmConfig.cloudReady(provider: String): Boolean = when (provider) {
    "ollama" -> ollamaModel.isNotBlank()
    "gemini" -> geminiKey.isNotBlank() && geminiModel.isNotBlank()
    "deepseek" -> deepseekKey.isNotBlank() && deepseekModel.isNotBlank()
    "apinex" -> apinexKey.isNotBlank() && apinexModel.isNotBlank()
    "tokenharbor" -> tokenharborKey.isNotBlank() && tokenharborModel.isNotBlank()
    // У своего сервера ключ не обязателен: локальный сервер часто работает без него.
    "custom" -> customEndpoint.isNotBlank() && customModel.isNotBlank()
    else -> false
}

/** The same reasoning switch for every cloud provider. */
fun LlmConfig.withCloudThinking(enabled: Boolean) =
    copy(ollamaThinking = enabled, geminiThinking = enabled, deepseekThinking = enabled, apinexThinking = enabled,
        tokenharborThinking = enabled, customThinking = enabled)

object LlmSettings {
    private const val PREFS = "llm_settings"
    private const val SECRET = "supertonic_llm_keys"
    // Queue observation must not decrypt API keys on a reader's Binder thread.
    fun enabled(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("mode", "OFF") != "OFF" &&
            (p.getBoolean("stress", false) || p.getBoolean("punctuation", true) || p.getBoolean("restore_yo", false) || p.getBoolean("multi_voice", false))
    }
    fun multiVoiceEnabled(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("mode", "OFF") != "OFF" && p.getBoolean("multi_voice", false)
    }
    /** The global first-person setting, without decrypting credentials. */
    fun firstPerson(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("first_person", false)
    fun idleSeconds(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt("idle_seconds", 120).coerceIn(30, 600)
    fun aheadChars(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt("ahead_chars", 16000).coerceIn(4000, 48000)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(SECRET, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(SECRET, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    @Synchronized private fun decrypt(value: String): String = runCatching {
        if (value.isEmpty()) "" else {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
        }
    }.getOrDefault("")

    fun load(context: Context): LlmConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return LlmConfig(
            mode = runCatching { LlmMode.valueOf(p.getString("mode", "OFF")!!) }.getOrDefault(LlmMode.OFF),
            ollamaEndpoint = p.getString("ollama_endpoint", "https://ollama.com")!!,
            ollamaModel = p.getString("ollama_model", "")!!, geminiModel = p.getString("gemini_model", "")!!,
            ollamaKey = decrypt(p.getString("ollama_key", "")!!), geminiKey = decrypt(p.getString("gemini_key", "")!!),
            deepseekModel = p.getString("deepseek_model", DeepSeekApi.DEFAULT_MODEL)!!,
            deepseekKey = decrypt(p.getString("deepseek_key", "")!!),
            deepseekThinking = p.getBoolean("deepseek_thinking", false),
            preferDeepseek = p.getBoolean("prefer_deepseek", false),
            apinexModel = p.getString("apinex_model", ApinexApi.DEFAULT_MODEL)!!,
            apinexKey = decrypt(p.getString("apinex_key", "")!!),
            apinexThinking = p.getBoolean("apinex_thinking", false),
            preferApinex = p.getBoolean("prefer_apinex", false),
            tokenharborModel = p.getString("tokenharbor_model", TokenHarborApi.DEFAULT_MODEL)!!,
            tokenharborKey = decrypt(p.getString("tokenharbor_key", "")!!),
            tokenharborThinking = p.getBoolean("tokenharbor_thinking", false),
            preferTokenharbor = p.getBoolean("prefer_tokenharbor", false),
            customEndpoint = p.getString("custom_endpoint", "")!!,
            customKey = decrypt(p.getString("custom_key", "")!!),
            customModel = p.getString("custom_model", "")!!,
            customThinking = p.getBoolean("custom_thinking", false),
            preferCustom = p.getBoolean("prefer_custom", false),
            // Старые тумблеры «в авторежиме сначала …» переносятся в очередь один раз.
            priority = p.getString("cloud_priority", "")!!.split(';').filter { it.isNotBlank() }.ifEmpty {
                when {
                    p.getBoolean("prefer_custom", false) -> listOf("custom")
                    p.getBoolean("prefer_tokenharbor", false) -> listOf("tokenharbor")
                    p.getBoolean("prefer_apinex", false) -> listOf("apinex")
                    p.getBoolean("prefer_deepseek", false) -> listOf("deepseek")
                    p.getBoolean("prefer_gemini", false) -> listOf("gemini")
                    else -> listOf("ollama")
                }
            },
            preferGemini = p.getBoolean("prefer_gemini", false), gpu = p.getBoolean("gpu", true),
            ollamaThinking = p.getBoolean("ollama_thinking", false),
            geminiThinking = p.getBoolean("gemini_thinking", false),
            localThinking = p.getBoolean("local_thinking", false),
            idleSeconds = p.getInt("idle_seconds", 120).coerceIn(30, 600),
            punctuation = p.getBoolean("punctuation", true), stress = p.getBoolean("stress", false),
            aheadChars = aheadChars(context), restoreYo = p.getBoolean("restore_yo", false), multiVoice = p.getBoolean("multi_voice", false),
            firstPerson = p.getBoolean("first_person", false)
        )
    }
    fun save(context: Context, c: LlmConfig) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (c.mode != LlmMode.OFF) edit.putString("last_mode", c.mode.name)
        edit
            .putString("mode", c.mode.name).putString("ollama_endpoint", c.ollamaEndpoint.trim().trimEnd('/'))
            .putString("ollama_model", c.ollamaModel.trim()).putString("gemini_model", c.geminiModel.trim().removePrefix("models/"))
            .putString("ollama_key", encrypt(c.ollamaKey.trim())).putString("gemini_key", encrypt(c.geminiKey.trim()))
            .putString("deepseek_model", c.deepseekModel.trim()).putString("deepseek_key", encrypt(c.deepseekKey.trim()))
            .putBoolean("deepseek_thinking", c.deepseekThinking).putBoolean("prefer_deepseek", c.preferDeepseek)
            .putString("apinex_model", c.apinexModel.trim()).putString("apinex_key", encrypt(c.apinexKey.trim()))
            .putBoolean("apinex_thinking", c.apinexThinking).putBoolean("prefer_apinex", c.preferApinex)
            .putString("tokenharbor_model", c.tokenharborModel.trim()).putString("tokenharbor_key", encrypt(c.tokenharborKey.trim()))
            .putBoolean("tokenharbor_thinking", c.tokenharborThinking).putBoolean("prefer_tokenharbor", c.preferTokenharbor)
            .putString("custom_endpoint", c.customEndpoint.trim().trimEnd('/')).putString("custom_key", encrypt(c.customKey.trim()))
            .putString("custom_model", c.customModel.trim())
            .putBoolean("custom_thinking", c.customThinking).putBoolean("prefer_custom", c.preferCustom)
            .putString("cloud_priority", c.priority.map { it.trim() }.filter { it.isNotBlank() }.take(3).joinToString(";"))
            .putBoolean("prefer_gemini", c.preferGemini).putBoolean("gpu", c.gpu)
            .putBoolean("ollama_thinking", c.ollamaThinking)
            .putBoolean("gemini_thinking", c.geminiThinking)
            .putBoolean("local_thinking", c.localThinking)
            .putInt("idle_seconds", c.idleSeconds.coerceIn(30, 600))
            .putBoolean("punctuation", c.punctuation).putBoolean("stress", c.stress)
            .putBoolean("restore_yo", c.restoreYo).putBoolean("multi_voice", c.multiVoice).putBoolean("first_person", c.firstPerson)
            .putInt("ahead_chars", c.aheadChars.coerceIn(4000, 48000)).apply()
        com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }
    fun previousMode(context: Context) = runCatching {
        LlmMode.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_mode", "AUTO")!!)
    }.getOrDefault(LlmMode.AUTO).takeUnless { it == LlmMode.OFF } ?: LlmMode.AUTO
}
