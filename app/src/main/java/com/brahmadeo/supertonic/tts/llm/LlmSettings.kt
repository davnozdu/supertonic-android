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
    GEMINI("Gemini"), LOCAL("Автономно: Gemma 4")
}

data class LlmConfig(
    val mode: LlmMode = LlmMode.OFF,
    val ollamaEndpoint: String = "https://ollama.com",
    val ollamaModel: String = "",
    val geminiModel: String = "",
    val ollamaKey: String = "",
    val geminiKey: String = "",
    val preferGemini: Boolean = false,
    val gpu: Boolean = true,
    val idleSeconds: Int = 120,
    val punctuation: Boolean = true,
    val stress: Boolean = true
)

object LlmSettings {
    private const val PREFS = "llm_settings"
    private const val SECRET = "supertonic_llm_keys"
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
            preferGemini = p.getBoolean("prefer_gemini", false), gpu = p.getBoolean("gpu", true),
            idleSeconds = p.getInt("idle_seconds", 120).coerceIn(30, 600),
            punctuation = p.getBoolean("punctuation", true), stress = p.getBoolean("stress", true)
        )
    }
    fun save(context: Context, c: LlmConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", c.mode.name).putString("ollama_endpoint", c.ollamaEndpoint.trim().trimEnd('/'))
            .putString("ollama_model", c.ollamaModel.trim()).putString("gemini_model", c.geminiModel.trim().removePrefix("models/"))
            .putString("ollama_key", encrypt(c.ollamaKey.trim())).putString("gemini_key", encrypt(c.geminiKey.trim()))
            .putBoolean("prefer_gemini", c.preferGemini).putBoolean("gpu", c.gpu)
            .putInt("idle_seconds", c.idleSeconds.coerceIn(30, 600))
            .putBoolean("punctuation", c.punctuation).putBoolean("stress", c.stress).apply()
        LlmPreparation.settingsChanged()
    }
}
