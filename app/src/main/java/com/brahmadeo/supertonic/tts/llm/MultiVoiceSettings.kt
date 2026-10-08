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
        val author = p.getString(key(ctx, VoiceRole.AUTHOR), "").orEmpty().takeIf { it in voices } ?: current
        return VoiceRoleDefaults.select(AssetManager.getModelType(ctx), role, author, voices)
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
            // A recognised character of a prepared book has its own voice; otherwise the role's voice («прочие»).
            val mixedFile = part.voice?.let { com.brahmadeo.supertonic.tts.books.BookVoiceCatalog.file(ctx, it) }
            val voice = part.voice?.takeIf { it in AssetManager.russianVoices(ctx) } ?: selected(ctx, part.role)
            val file = mixedFile ?: AssetManager.voiceFile(ctx, voice)
            com.brahmadeo.supertonic.tts.utils.DiagLog.i("MultiVoice", "Route role=${part.role} character=${part.character} voice=${file.parentFile?.name}/${file.name} source=${SpeechTextTrace.fingerprint(part.text)}")
            part.text to if (file.isFile) file.absolutePath else fallbackStyle
        }
    }
}
