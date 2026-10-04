package com.brahmadeo.supertonic.tts.pocket

internal object PocketText {
    /** Shtorm was trained with combining acute, whereas the common preparation uses + before vowels. */
    fun prepare(input: String): String = Regex("\\+([аеёиоуыэюяАЕЁИОУЫЭЮЯ])").replace(
        com.brahmadeo.supertonic.tts.utils.BookTextSpacing.normalize(input)) { "${it.groupValues[1]}\u0301" }
        .replace(Regex("[\\t\\r\\n ]+")," ").trim()
    fun chunks(input: String, limit: Int = 180): List<String> {
        require(limit>=32)
        val chunks=mutableListOf<String>()
        var rest=prepare(input)
        while(rest.length>limit) {
            val punctuation=(0 until limit).lastOrNull { rest[it] in ".!?;:,…" && it>=limit/3 }
            var end=punctuation?.plus(1) ?: rest.lastIndexOf(' ',limit).takeIf { it>0 } ?: limit
            if(end<rest.length && rest[end]=='\u0301') end++
            chunks.add(rest.substring(0,end).trim())
            rest=rest.substring(end).trimStart()
        }
        if(rest.isNotEmpty()) chunks.add(rest)
        return chunks
    }
}
