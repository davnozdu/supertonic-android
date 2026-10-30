package com.brahmadeo.supertonic.tts.books.prepare

import com.brahmadeo.supertonic.tts.llm.VoiceRole
import com.brahmadeo.supertonic.tts.llm.VoiceRoleText

/** Складывает две разметки реплик одного абзаца: решение модели и решение локальных правил.
 * Правило простое и намеренно осторожное: где модель назвала персонажа (мужской, женский, с именем) — верим ей;
 * где она оставила «автор», а правила видят реплику — берём правила. Так ни одна реплика не читается голосом
 * рассказчика только потому, что модель её пропустила.
 * Текст на выходе посимвольно равен абзацу: индекс реплик строится по словам, любая потеря символа сдвинула бы
 * все окна и голоса поехали бы. */
object RoleMerge {
    fun merge(model: List<VoiceRoleText>?, local: List<VoiceRoleText>?): List<VoiceRoleText>? {
        if (model == null) return local
        if (local == null) return model
        val text = model.joinToString("") { it.text }
        if (text != local.joinToString("") { it.text }) return model
        class At(val role: VoiceRole, val character: String?, val voice: String?)
        fun spans(parts: List<VoiceRoleText>): Array<At?> {
            val out = arrayOfNulls<At>(text.length)
            var pos = 0
            for (part in parts) {
                for (k in part.text.indices) if (pos + k < out.size) out[pos + k] = At(part.role, part.character, part.voice)
                pos += part.text.length
            }
            return out
        }
        val a = spans(model)
        val b = spans(local)
        val chosen = Array<At?>(text.length) { i ->
            val m = a[i]
            if (m == null || m.role == VoiceRole.AUTHOR) (b[i] ?: m) else m
        }
        val out = mutableListOf<VoiceRoleText>()
        var from = 0
        while (from < text.length) {
            val first = chosen[from]
            if (first == null) { from++; continue }
            var end = from + 1
            while (end < text.length) {
                val next = chosen[end] ?: break
                if (next.role != first.role || next.character != first.character) break
                end++
            }
            out += VoiceRoleText(text.substring(from, end), first.role, first.character, first.voice)
            from = end
        }
        return out.takeIf { it.joinToString("") { p -> p.text } == text } ?: model
    }
}
