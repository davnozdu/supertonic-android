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
    /** The author's voice is also the main voice of the model (MyTTS screen, «MyTTS Один голос»), and back. */
    fun save(ctx: Context, role: VoiceRole, voice: String) {
        require(voice in AssetManager.russianVoices(ctx))
        ctx.getSharedPreferences("SupertonicPrefs", 0).edit().putString(key(ctx, role), voice)
            .apply { if (role == VoiceRole.AUTHOR) putString("selected_voice", "$voice.json") }.apply()
        com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
    }
    /** All synthesis paths share the same validated segmentation and voice selection. */
    fun parts(ctx: Context, text: String, plan: List<VoiceRoleText>, fallbackStyle: String): List<Pair<String, String>> =
        routes(ctx, text, plan, fallbackStyle).map { it.text to it.style }

    /** A piece of text, its voice file and whether it is a character's line (not the author / narrator / «я»): only
     * those get the room's reverb («Акустика помещения»). */
    data class Route(val text: String, val style: String, val dialogue: Boolean)
    fun routes(ctx: Context, text: String, plan: List<VoiceRoleText>, fallbackStyle: String): List<Route> {
        if (!AssetManager.isRussianModel(ctx) || !LlmSettings.multiVoiceEnabled(ctx)) return listOf(Route(text, fallbackStyle, false))
        return VoiceRolePlan.safe(text, plan).map { part ->
            // A recognised character of a prepared book has its own voice; otherwise the role's voice («прочие»).
            val mixedFile = part.voice?.let { com.brahmadeo.supertonic.tts.books.BookVoiceCatalog.file(ctx, it) }
            val voice = part.voice?.takeIf { it in AssetManager.russianVoices(ctx) } ?: selected(ctx, part.role)
            val file = mixedFile ?: AssetManager.voiceFile(ctx, voice)
            com.brahmadeo.supertonic.tts.utils.DiagLog.i("MultiVoice", "Route role=${part.role} character=${part.character} voice=${file.parentFile?.name}/${file.name} source=${SpeechTextTrace.fingerprint(part.text)}")
            Route(part.text, if (file.isFile) file.absolutePath else fallbackStyle, part.role != VoiceRole.AUTHOR)
        }
    }
}
