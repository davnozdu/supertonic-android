package com.brahmadeo.supertonic.tts.llm

enum class VoiceRole { AUTHOR, MALE, FEMALE }
data class VoiceRoleRange(val start: Int, val end: Int, val role: VoiceRole, val certain: Boolean = true)
data class VoiceRoleText(val text: String, val role: VoiceRole)

/** Indices refer to original whitespace units. Model text is never used for voice routing. */
object VoiceRolePlan {
    fun units(text: String): List<String> {
        val matches = Regex("[^\\s\\p{Z}\uFEFF]+[\\s\\p{Z}\uFEFF]*").findAll(text).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexed { i, m -> if (i == 0) text.substring(0, m.range.last + 1) else m.value }
    }
    fun render(text: String, ranges: List<VoiceRoleRange>): List<VoiceRoleText>? {
        val units = units(text)
        if (units.isEmpty() || ranges.isEmpty() || ranges.size > 128) return null
        var cursor = 0
        val result = mutableListOf<VoiceRoleText>()
        for (range in ranges) {
            if (range.start != cursor || range.end <= cursor || range.end > units.size) return null
            val role = if (range.certain) range.role else VoiceRole.AUTHOR
            val part = units.subList(cursor, range.end).joinToString("")
            if (result.lastOrNull()?.role == role) {
                val old = result.removeAt(result.lastIndex)
                result += VoiceRoleText(old.text + part, role)
            } else result += VoiceRoleText(part, role)
            cursor = range.end
        }
        if (cursor != units.size || result.joinToString("") { p -> p.text } != text) return null
        // Never synthesize a standalone dash/quote when a provider gives it its own role.
        for (i in result.indices.reversed()) {
            if (result.size > 1 && result[i].text.none { it.isLetterOrDigit() }) {
                val punctuation = result.removeAt(i)
                if (i < result.size) result[i] = result[i].copy(text = punctuation.text + result[i].text)
                else result[i-1] = result[i-1].copy(text = result[i-1].text + punctuation.text)
            }
        }
        val merged = mutableListOf<VoiceRoleText>()
        result.forEach { part ->
            if (merged.lastOrNull()?.role == part.role) {
                val old = merged.removeAt(merged.lastIndex)
                merged += old.copy(text = old.text + part.text)
            } else merged += part
        }
        return merged
    }
    fun safe(text: String, plan: List<VoiceRoleText>): List<VoiceRoleText> =
        if (plan.isNotEmpty() && plan.size <= 128 && plan.all { it.text.isNotEmpty() } && plan.joinToString("") { it.text } == text) plan
        else listOf(VoiceRoleText(text, VoiceRole.AUTHOR))
}

/** Bounded context, isolated per TTS client; flush, stop and settings changes clear it. */
internal class VoiceRoleContext(private val limit: Int = 1800, private val callers: Int = 8) {
    private val recent = linkedMapOf<Any, String>()
    @Synchronized fun get(caller: Any): String = recent[caller].orEmpty()
    @Synchronized fun append(caller: Any, texts: List<String>) {
        val before = recent.remove(caller).orEmpty()
        recent[caller] = (before + "\n" + texts.joinToString("\n")).takeLast(limit)
        while (recent.size > callers) recent.remove(recent.keys.first())
    }
    @Synchronized fun clear(caller: Any) { recent.remove(caller) }
    @Synchronized fun clear() { recent.clear() }
}
