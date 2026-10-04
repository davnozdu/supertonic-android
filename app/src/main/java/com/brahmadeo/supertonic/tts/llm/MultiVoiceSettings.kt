package com.brahmadeo.supertonic.tts.llm

import android.content.Context
import com.brahmadeo.supertonic.tts.utils.AssetManager

object MultiVoiceSettings {
    private fun key(ctx: Context, role: VoiceRole) = "multi_voice_${AssetManager.getModelType(ctx)}_${role.name.lowercase()}"
    fun selected(ctx: Context, role: VoiceRole): String {
        val voices = AssetManager.russianVoices(ctx)
        val p = ctx.getSharedPreferences("SupertonicPrefs", 0)
        val configured = p.getString(key(ctx, role), "").orEmpty()
        if (configured in voices) return configured
        val current = p.getString("selected_voice", "").orEmpty().removeSuffix(".json")
        val preferred = when (role) {
            VoiceRole.AUTHOR -> current
            VoiceRole.MALE -> when { AssetManager.isTera(ctx) -> "ru_m5"; AssetManager.isPocket(ctx) -> "marius"; AssetManager.getModelType(ctx) == AssetManager.SILERO_CIS_MODEL -> "ru_alexandr"; else -> "aidar" }
            VoiceRole.FEMALE -> when { AssetManager.isTera(ctx) -> "ru_f1"; AssetManager.isPocket(ctx) -> "alba"; AssetManager.getModelType(ctx) == AssetManager.SILERO_CIS_MODEL -> "ru_ekaterina"; else -> "kseniya" }
        }
        return voices.firstOrNull { it.equals(preferred, true) } ?: voices.firstOrNull().orEmpty()
    }
    fun save(ctx: Context, role: VoiceRole, voice: String) {
        require(voice in AssetManager.russianVoices(ctx))
        ctx.getSharedPreferences("SupertonicPrefs", 0).edit().putString(key(ctx, role), voice).apply()
        com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }
    /** All synthesis paths share the same validated segmentation and voice selection. */
    fun parts(ctx: Context, text: String, plan: List<VoiceRoleText>, fallbackStyle: String): List<Pair<String, String>> {
        if (!AssetManager.isRussianModel(ctx) || !LlmSettings.multiVoiceEnabled(ctx)) return listOf(text to fallbackStyle)
        return VoiceRolePlan.safe(text, plan).map { part ->
            val file = AssetManager.voiceFile(ctx, selected(ctx, part.role))
            android.util.Log.i("MultiVoice", "Route role=${part.role} voice=${file.parentFile?.name}/${file.name} source=${SpeechTextTrace.fingerprint(part.text)}")
            part.text to if (file.isFile) file.absolutePath else fallbackStyle
        }
    }
}
