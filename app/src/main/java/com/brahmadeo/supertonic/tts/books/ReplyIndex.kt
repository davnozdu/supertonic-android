package com.brahmadeo.supertonic.tts.books

import com.brahmadeo.supertonic.tts.llm.VoiceRole
import com.brahmadeo.supertonic.tts.llm.VoiceRoleText

/** Who speaks each word of the dialogue paragraphs of a prepared book («Разметка реплик»), so any reader gets the
 * voices at once, without the LLM. Readers cut the text their own way — Moon+ by paragraph, Readest by sentence and
 * further at every inline tag of the book (a piece may start with «, »), without the leading dash — so the index is
 * by word: a word is found by its letters and the [WINDOW] − 1 words before it (across paragraphs, in reading order).
 * Punctuation never matters; a word whose key two places share with different voices is left out (its neighbours
 * decide). Stored compactly: sorted 8-byte keys + 2-byte voice numbers, base64 ("codes" lists the voices). */
class ReplyIndex private constructor(private val keys: LongArray, private val values: ShortArray, private val codes: List<Voice>) {
    data class Voice(val role: VoiceRole, val speaker: String?)

    val size get() = keys.size

    private fun voiceOf(key: Long): Voice? {
        val i = keys.binarySearch(key)
        return if (i >= 0) codes.getOrNull(values[i].toInt()) else null
    }

    /** Voice parts of [text] (they join back to [text] exactly) after the words [before] (the reader's previous text,
     * at most [WINDOW] − 1 kept), or null when no word of it is in the index. */
    fun resolve(text: String, before: List<String>, voice: (VoiceRole, String?) -> String?): List<VoiceRoleText>? {
        val units = unit.findAll(text).toList()
        val words = units.indices.filter { letters(units[it].value).isNotEmpty() }
        if (words.isEmpty()) return null
        val window = ArrayDeque(before.takeLast(WINDOW - 1))
        val found = words.map { u ->
            window.addLast(letters(units[u].value)); while (window.size > WINDOW) window.removeFirst()
            // Короткое окно — начало куска: там ключ тоже есть, если слова стоят в начале абзаца книги.
            voiceOf(key(window))
        }
        if (found.all { it == null }) return null
        // A word not found takes the voice of the word before it (or, at the start, the first one found).
        val filled = ArrayList<Voice>(found.size)
        var last = found.first { it != null }!!
        for (v in found) { if (v != null) last = v; filled += last }
        val cuts = mutableListOf(0); val voices = mutableListOf(filled[0])
        for (i in 1 until words.size) if (filled[i] != filled[i - 1]) {
            // Punctuation right before a word (a dash before a remark) goes with that word.
            var u = words[i]
            while (u > 0 && letters(units[u - 1].value).isEmpty()) u--
            val cut = units[u].range.first
            if (cut > cuts.last()) { cuts += cut; voices += filled[i] }
        }
        val parts = voices.indices.map { i ->
            val v = voices[i]
            VoiceRoleText(text.substring(cuts[i], cuts.getOrNull(i + 1) ?: text.length), v.role, v.speaker, voice(v.role, v.speaker))
        }
        return parts.takeIf { it.joinToString("") { p -> p.text } == text }
    }

    companion object {
        const val ALGORITHM = "words4-fnv1a64"
        const val WINDOW = 4
        private const val SPACE = "\\s\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000\\uFEFF"
        private val unit = Regex("[^$SPACE]+[$SPACE]*")
        private val dialogue = Regex("^[$SPACE]*[—–\\-]|[«„“\"]")

        /** Lowercase letters and digits of a word (ё → е, no stress marks). */
        fun letters(word: String) = BookFingerprint.letters(word)
        fun key(window: Collection<String>) = BookFingerprint.hash(window.joinToString(" "))
        /** The words of [text] in reading order (for the window of the next text). */
        fun words(text: String): List<String> = unit.findAll(text).map { letters(it.value) }.filter { it.isNotEmpty() }.toList()
        /** A paragraph that may hold speech; the others are narration and are not in the index (the author reads them). */
        fun isDialogue(text: String) = dialogue.containsMatchIn(text)

        fun code(v: Voice) = (when (v.role) { VoiceRole.MALE -> "m"; VoiceRole.FEMALE -> "f"; else -> "a" }) + (v.speaker?.let { ":$it" } ?: "")
        fun voice(code: String): Voice? {
            val f = code.split(':')
            val role = when (f[0]) { "m" -> VoiceRole.MALE; "f" -> VoiceRole.FEMALE; "a" -> VoiceRole.AUTHOR; else -> return null }
            return Voice(role, f.getOrNull(1)?.ifBlank { null }?.takeIf { role != VoiceRole.AUTHOR })
        }

        /** Builder: feed every paragraph of the book in reading order; [plan] is the LLM's voices of a dialogue paragraph. */
        class Builder {
            private val window = ArrayDeque<String>()
            private val entries = HashMap<Long, Voice>()
            private val clash = HashSet<Long>()
            val size get() = entries.size
            val clashes get() = clash.size
            fun add(paragraph: String, plan: List<VoiceRoleText>?) {
                val roleAt: Array<VoiceRoleText?>? = plan?.takeIf { it.joinToString("") { p -> p.text } == paragraph }?.let { p ->
                    val a = arrayOfNulls<VoiceRoleText>(paragraph.length); var pos = 0
                    for (part in p) { for (i in part.text.indices) a[pos + i] = part; pos += part.text.length }
                    a
                }
                for (m in unit.findAll(paragraph)) {
                    val w = letters(m.value); if (w.isEmpty()) continue
                    window.addLast(w); while (window.size > WINDOW) window.removeFirst()
                    // Начало абзаца: четырёх слов ещё нет, но реплика уже началась. Раньше такие слова молча
                    // выпадали, и первая реплика абзаца читалась голосом рассказчика.
                    if (roleAt == null || window.isEmpty()) continue
                    val part = roleAt[m.range.first + m.value.indexOfFirst { it.isLetterOrDigit() }] ?: continue
                    val v = Voice(part.role, part.character?.takeIf { part.role != VoiceRole.AUTHOR })
                    val k = key(window)
                    if (k in clash) continue
                    val old = entries.putIfAbsent(k, v)
                    if (old != null && old != v) { entries.remove(k); clash += k }
                }
            }
            /** (codes, base64 index) for the package. */
            fun export(): Pair<List<String>, String> {
                val codes = entries.values.map(::code).distinct().sorted()
                val number = codes.withIndex().associate { it.value to it.index }
                val sorted = entries.entries.sortedBy { it.key }
                val bytes = java.nio.ByteBuffer.allocate(sorted.size * 10)
                for ((k, v) in sorted) { bytes.putLong(k); bytes.putShort(number.getValue(code(v)).toShort()) }
                return codes to java.util.Base64.getEncoder().encodeToString(bytes.array())
            }
        }

        fun parse(codes: List<String>, index: String): ReplyIndex {
            val voices = codes.map { requireNotNull(voice(it)) { "Некорректная разметка реплики" } }
            require(voices.size <= Short.MAX_VALUE) { "Слишком много голосов в разметке" }
            val bytes = java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(index))
            require(bytes.remaining() % 10 == 0) { "Некорректная разметка реплик" }
            val n = bytes.remaining() / 10
            val keys = LongArray(n); val values = ShortArray(n)
            for (i in 0 until n) {
                keys[i] = bytes.long; values[i] = bytes.short
                require(values[i] in 0 until voices.size) { "Некорректная разметка реплик" }
                require(i == 0 || keys[i] > keys[i - 1]) { "Разметка реплик не упорядочена" }
            }
            return ReplyIndex(keys, values, voices)
        }

        val EMPTY = ReplyIndex(LongArray(0), ShortArray(0), emptyList())
    }
}
