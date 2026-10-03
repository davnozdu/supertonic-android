package com.brahmadeo.supertonic.tts.silero

import java.util.Locale

/** v5.5's native alphabet and sentence-type inputs. Explicit stress wins. */
internal object SileroText {
    private const val symbols = "_~|!+,-.:;?абвгдежзийклмнопрстуфхцчшщъыьэюяё–… "
    private val acute = Regex("([аеёиоуыэюя])\u0301")
    private val wh = setOf("где", "зачем", "как", "какая", "какие", "какой", "когда", "кто", "кого", "кому", "куда", "откуда", "почему", "сколько", "что", "чего", "чем", "чей")
    private val fillers = setOf("ну", "а", "и", "вот", "так", "скажи", "скажите", "пожалуйста")
    fun prepare(input: String): String {
        val manual = acute.replace(com.brahmadeo.supertonic.tts.utils.BookTextSpacing.normalize(input).lowercase(Locale.ROOT)) { "+${it.groupValues[1]}" }
        val stress = Regex("[а-яё+]+").replace(manual) { word ->
            if('ё' in word.value && '+' !in word.value) word.value.replace("ё","+ё") else word.value
        }
        return stress.replace('—', '–').replace('\n', ' ').replace('\t', ' ')
            .map { c -> when {
                c in symbols && c !in "_~|" -> c
                c.isLetter() || Character.getType(c)==Character.NON_SPACING_MARK.toInt() -> null
                else -> ' '
            } }.filterNotNull().joinToString("")
            .replace(Regex(" +"), " ").trim()
    }
    fun phrases(input: String, extraSentencePauseMs: Int = 0): List<String> {
        val spaced=com.brahmadeo.supertonic.tts.utils.BookTextSpacing.normalize(input)
        val sentences=if(extraSentencePauseMs>0) com.brahmadeo.supertonic.tts.tera.TeraPunctuationPauses
            .split(spaced,0,extraSentencePauseMs,sentenceOnly=true).map { it.text } else listOf(spaced)
        return sentences.flatMap { bounded(prepare(it)) }
    }
    fun bounded(prepared: String, limit: Int = 1000): List<String> {
        require(limit in 32..1200)
        val result=mutableListOf<String>();var remaining=prepared.trim()
        while(remaining.length>limit) {
            var end=remaining.lastIndexOf(' ',limit)
            if(end<limit/2) end=limit
            if(end>0 && remaining[end-1]=='+') end--
            result+=remaining.substring(0,end).trim();remaining=remaining.substring(end).trimStart()
        }
        if(remaining.isNotEmpty()) result+=remaining
        return result
    }
    fun sequence(prepared: String): LongArray = longArrayOf(2) +
        prepared.map { symbols.indexOf(it).toLong() }.toLongArray() + longArrayOf(1)
    data class ModelInput(val text: String, val addedEndDot: Boolean)
    private val oxytoneEnd = Regex("[аеёиоуыэюя]\\S*\\+[аеёиоуыэюя][^\\sаеёиоуыэюя]*$", RegexOption.IGNORE_CASE)
    private val shortEnd = Regex("^([^\\p{L}]*[бвгджзйклмнпрстфхцчшщ]{1,2}\\+?)([аеёиоуыэюя])(?=[.!?…][^\\p{L}]*$)", RegexOption.IGNORE_CASE)
    private val smiEnd = Regex("(?<![\\p{L}+])(см\\+)(и)(?=[.!?…][^\\p{L}]*$)", RegexOption.IGNORE_CASE)
    /** Silero v5.5 end-symbol corrections, adapted from RuVoice Stress.modelEnd/stretchShort.
     * Only model input changes: source text, cache keys and reader highlighting stay intact.
     * Do not introduce inter-word pauses or change question/exclamation punctuation. */
    fun modelInput(prepared: String): ModelInput {
        val text = prepared.trimEnd()
        if (text.isEmpty()) return ModelInput(text, false)
        val oxy = oxytoneEnd.containsMatchIn(text.removeSuffix("."))
        val added = text.last().isLetter() && !oxy
        val ended = when {
            added -> "$text."
            oxy && text.endsWith('.') && !text.endsWith("..") -> text.dropLast(1)
            else -> text
        }
        return ModelInput(smiEnd.replace(shortEnd.replace(ended, "$1$2$2"), "$1$2$2"), added)
    }
    fun typeIds(prepared: String, expressive: Boolean): LongArray {
        val ids = LongArray(prepared.length + 2)
        if (!expressive) return ids
        var start = 0
        for (match in Regex("[.!?…]+(?: +|$)").findAll(prepared)) {
            val end = match.range.last + 1
            val kind = type(prepared.substring(start, end))
            for (i in start until end) ids[i + 1] = kind
            if (start == 0) ids[0] = kind
            start = end
        }
        if (start < prepared.length) {
            val kind = type(prepared.substring(start))
            for (i in start until prepared.length) ids[i + 1] = kind
            if (start == 0) ids[0] = kind
        }
        ids[ids.lastIndex] = ids[ids.lastIndex - 1]
        return ids
    }
    fun type(prepared: String): Long {
        val text = prepared.replace("+", "").trimEnd()
        if (text.endsWith('?') || text.endsWith("?!")) {
            if (Regex("(?:,|\\s)(?:правда|верно|да|не так ли|не правда ли)\\s*\\?!?$").containsMatchIn(text)) return 4
            val first = Regex("[а-яё]+").findAll(text).map { it.value }.filter { it !in fillers }.take(4).toList()
            if (first.any { it in wh }) return 1
            if (Regex("(?<![а-яё])или(?![а-яё])").containsMatchIn(text)) return 3
            return 2
        }
        return if (text.endsWith('!')) 5 else 0
    }
}
