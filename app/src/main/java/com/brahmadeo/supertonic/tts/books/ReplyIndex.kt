package com.brahmadeo.supertonic.tts.books

import com.brahmadeo.supertonic.tts.llm.VoiceRole
import com.brahmadeo.supertonic.tts.llm.VoiceRoleText

/** Who speaks each sentence of the dialogue paragraphs of a prepared book («Разметка реплик»), so a reader gets the
 * voices at once, without the LLM (Readest sends one sentence at a time; Moon+ would otherwise wait for the roles).
 * A sentence is found by two keys: its letters after the last letters of the previous sentence (short replies like
 * «— Да.» are unique only with context), and its own first 48 letters when it is long enough (reading started
 * mid-dialogue). Parts are stored by word number, so a dash a reader drops (Readest) changes nothing.
 * Code: "0:m:myshkin;4:a" — from word 0 Мышкин (male), from word 4 the author. */
object ReplyIndex {
    data class Part(val word: Int, val role: VoiceRole, val speaker: String?)
    const val ALGORITHM = "ctx24-fnv1a64-48"

    /** Sentence boundaries close to Intl.Segmenter (Readest): end punctuation, space, a capital, dash or quote. */
    private const val SPACE = "\\s\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000\\uFEFF"
    private val boundary = Regex("(?<=[.!?…])[$SPACE]+(?=[—–\\-«\"„(A-ZА-ЯЁ])")
    private val unit = Regex("[^$SPACE]+[$SPACE]*")
    private const val CONTEXT = 24

    fun sentences(text: String): List<String> {
        val out = mutableListOf<String>(); var start = 0
        for (m in boundary.findAll(text)) { out += text.substring(start, m.range.last + 1); start = m.range.last + 1 }
        if (start < text.length) out += text.substring(start)
        return out.filter { it.isNotBlank() }
    }

    private fun hasWord(s: String) = s.any { it.isLetterOrDigit() }

    /** Keys of [sentence] after [previous]: with context (always) and alone (≥ 24 letters). */
    fun keys(previous: String?, sentence: String): List<Long> {
        val own = BookFingerprint.letters(sentence, BookFingerprint.LETTERS)
        if (own.isEmpty()) return emptyList()
        val keys = mutableListOf<Long>()
        val before = previous?.let { BookFingerprint.letters(it).takeLast(CONTEXT) }.orEmpty()
        if (before.isNotEmpty()) keys += BookFingerprint.hash("$before|$own")
        if (own.length >= BookFingerprint.MIN_LETTERS) keys += BookFingerprint.hash("|$own")
        return keys
    }

    fun encode(parts: List<Part>): String = parts.joinToString(";") { p ->
        "${p.word}:${when (p.role) { VoiceRole.MALE -> "m"; VoiceRole.FEMALE -> "f"; else -> "a" }}" + (p.speaker?.let { ":$it" } ?: "")
    }

    fun decode(code: String): List<Part>? = runCatching {
        code.split(';').map { item ->
            val f = item.split(':')
            Part(f[0].toInt(), when (f[1]) { "m" -> VoiceRole.MALE; "f" -> VoiceRole.FEMALE; "a" -> VoiceRole.AUTHOR; else -> error("role") },
                f.getOrNull(2)?.ifBlank { null })
        }.also { require(it.isNotEmpty() && it.first().word == 0 && it.zipWithNext().all { (a, b) -> b.word > a.word }) }
    }.getOrNull()

    /** Index entries of a paragraph from its voice plan (the LLM's roles over the paragraph text): one per sentence. */
    fun entries(paragraph: String, plan: List<VoiceRoleText>, previous: String?): List<Pair<List<Long>, String>> {
        if (plan.joinToString("") { it.text } != paragraph) return emptyList()
        // Role of every character of the paragraph.
        val roleAt = arrayOfNulls<VoiceRoleText>(paragraph.length)
        var pos = 0
        for (part in plan) { for (i in part.text.indices) roleAt[pos + i] = part; pos += part.text.length }
        val out = mutableListOf<Pair<List<Long>, String>>()
        var prev = previous; var offset = 0
        for (sentence in sentences(paragraph)) {
            val start = paragraph.indexOf(sentence, offset).also { offset = it + sentence.length }
            val parts = mutableListOf<Part>(); var word = 0
            for (m in unit.findAll(sentence)) {
                if (!hasWord(m.value)) continue
                // The part of the unit's first letter: a leading dash never decides.
                val at = start + m.range.first + m.value.indexOfFirst { it.isLetterOrDigit() }
                val r = roleAt[at] ?: continue
                val role = r.role; val speaker = if (role == VoiceRole.AUTHOR) null else r.character
                if (parts.lastOrNull()?.let { it.role == role && it.speaker == speaker } != true) parts += Part(word, role, speaker)
                word++
            }
            val keys = keys(prev, sentence)
            if (parts.isNotEmpty() && keys.isNotEmpty()) out += keys to encode(parts)
            prev = sentence
        }
        return out
    }

    private val dialogue = Regex("^[$SPACE]*[—–\\-]|«")
    /** A paragraph that may hold speech; the others are narration and are not in the index (the author reads them). */
    fun isDialogue(text: String) = dialogue.containsMatchIn(text)

    /** Each sentence of [text] with its parts from [lookup] (null where it is not in the index); [previous] is the
     * sentence a reader sent before [text]. */
    fun resolve(text: String, previous: String?, lookup: (Long) -> String?, voice: (VoiceRole, String?) -> String?): List<Pair<String, List<VoiceRoleText>?>> {
        val parts = sentences(text).takeIf { it.joinToString("") == text } ?: listOf(text)
        var prev = previous
        return parts.map { sentence ->
            val found = keys(prev, sentence).firstNotNullOfOrNull { k -> lookup(k)?.let(::decode) }?.let { apply(sentence, it, voice) }
            prev = sentence
            sentence to found
        }
    }

    /** Voice parts of a [sentence] as a reader sent it (its text joins back exactly), or null when [parts] do not fit.
     * Punctuation before a word goes with that word's part, except a dash before a remark, which is the author's. */
    fun apply(sentence: String, parts: List<Part>, voice: (VoiceRole, String?) -> String?): List<VoiceRoleText>? {
        val units = unit.findAll(sentence).toList()
        val wordUnits = units.indices.filter { hasWord(units[it].value) }
        if (wordUnits.isEmpty() || parts.last().word >= wordUnits.size) return null
        val cuts = parts.map { p ->
            if (p.word == 0) 0 else {
                var u = wordUnits[p.word]
                // Units without letters right before this word (a dash) join this part.
                while (u > 0 && !hasWord(units[u - 1].value)) u--
                units[u].range.first
            }
        }
        if (cuts.zipWithNext().any { (a, b) -> b <= a }) return null
        val out = parts.mapIndexed { i, p ->
            val text = sentence.substring(cuts[i], cuts.getOrNull(i + 1) ?: sentence.length)
            VoiceRoleText(text, p.role, p.speaker, voice(p.role, p.speaker))
        }.toMutableList()
        return out.takeIf { it.joinToString("") { p -> p.text } == sentence }
    }
}
