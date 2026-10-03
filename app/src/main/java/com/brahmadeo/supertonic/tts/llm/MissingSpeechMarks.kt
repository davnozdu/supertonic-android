package com.brahmadeo.supertonic.tts.llm

/** Fill only unmarked source words; keep LLM punctuation, letters and explicit marks. */
internal object MissingSpeechMarks {
    private val words=Regex("[+А-Яа-яЁё\\u0301]+")
    private val plus=Regex("\\+([АЕЁИОУЫЭЮЯаеёиоуыэюя])")
    private fun plain(s: String)=s.replace("+","").replace("\u0301","")
    fun merge(source: String, candidate: String, stress: Boolean, yo: Boolean, ambiguousYo: Set<String> = emptySet()): String {
        val original=words.findAll(source).toList()
        val suggested=words.findAll(candidate).toList()
        if(original.size!=suggested.size) return source
        val out=StringBuilder(source)
        for(i in original.indices.reversed()) {
            val old=original[i].value
            if(old.any { it in "+\u0301ёЁ" }) continue
            val replacement=plus.replace(suggested[i].value) { "${it.groupValues[1]}\u0301" }
            val clean=plain(replacement)
            if(clean.length!=old.length || clean.lowercase().replace('ё','е')!=old.lowercase().replace('ё','е')) continue
            val base=old.mapIndexed { index,c ->
                if(yo && old.lowercase() !in ambiguousYo && c in "еЕ" && clean[index].lowercaseChar()=='ё') {
                    if(c.isUpperCase()) 'Ё' else 'ё'
                } else c
            }.joinToString("")
            val mark=replacement.indexOf('\u0301')
            val validMark=stress && replacement.count { it=='\u0301' }==1 && mark>0 &&
                replacement[mark-1] in "АЕЁИОУЫЭЮЯаеёиоуыэюя" && mark<=base.length
            val completed=if(validMark) base.substring(0,mark)+"\u0301"+base.substring(mark) else base
            out.replace(original[i].range.first,original[i].range.last+1,completed)
        }
        return out.toString()
    }
}
