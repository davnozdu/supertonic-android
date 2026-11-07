package com.brahmadeo.supertonic.tts.books.prepare

import com.brahmadeo.supertonic.tts.llm.VoiceRoleText

/** A validated model plan is authoritative, including narration and first-person dialogue.
 * Local rules are a fallback only when the model supplied no valid plan. Repainting AUTHOR spans
 * changes deliberate narrator replies and author remarks into guessed characters. */
object RoleMerge {
    fun merge(model: List<VoiceRoleText>?, local: List<VoiceRoleText>?): List<VoiceRoleText>? =
        model?.takeIf { it.isNotEmpty() } ?: local
}
