package com.brahmadeo.supertonic.tts.books.prepare

/** Mechanical candidate extraction using only this book's statistics and the bundled names lexicon.
 * No character lists, external morphology dictionary or Android dependencies. */
class NameCandidates(private val names: Set<String>, private val check: () -> Unit = {}) {
    data class Candidate(val key: String, val kind: String, val scope: Int) {
        var id = ""
        var count = 0
        var speaker = 0
        val genders = linkedMapOf<String, Int>()
        val roles = linkedMapOf<String, Int>()
        val titles = linkedMapOf<String, Int>()
        val forms = linkedMapOf<String, Int>()
        val sections = linkedMapOf<Int, Int>()
        val examples = mutableListOf<String>()
        val together = linkedMapOf<String, Int>()
        val display get() = forms.ranked().firstOrNull()?.key ?: key
        fun genderSource(): Pair<String, String?> {
            for (source in listOf("verb", "Patr", "Title", "Surn")) {
                val m = genders["$source:m"] ?: 0; val f = genders["$source:f"] ?: 0
                if (m + f < if (source == "verb") 2 else 1) continue
                if (m >= 2 * f) return "m" to source
                if (f >= 2 * m) return "f" to source
            }
            return "?" to null
        }
        val gender get() = genderSource().first
    }
    data class Extraction(val collection: Boolean, val candidates: List<Candidate>)
    private data class Mention(val start: Int, val end: Int, val candidate: Candidate, val nominative: Boolean)
    private val capitalized = hashMapOf<String, Int>()
    private val lower = hashMapOf<String, Int>()
    private val inside = hashMapOf<String, Int>()
    private val bases = hashMapOf<String, String>()

    fun statistics(book: EpubBook) {
        capitalized.clear(); lower.clear(); inside.clear(); bases.clear()
        for (p in book.paragraphs) {
            check()
            for (m in WORD.findAll(p.text)) {
                val w = m.value; val k = key(w)
                if (w[0].isLowerCase()) lower.increment(k) else {
                    capitalized.increment(k)
                    if (!sentenceStart(p.text, m.range.first)) inside.increment(k)
                }
            }
        }
    }

    /** Every returned base is actually present with a capital in this book. */
    fun base(word: String): String = bases.getOrPut(key(word)) {
        val low = key(word)
        val options = linkedSetOf(low)
        for (ending in ENDINGS) if (low.endsWith(ending) && low.length - ending.length >= 3) {
            val stem = low.dropLast(ending.length)
            for (variant in VARIANTS) {
                val proposed = stem + variant
                if ((capitalized[proposed] ?: 0) >= maxOf(2, (capitalized[low] ?: 0) / 4)) options += proposed
            }
        }
        // A suffix which is itself a common nominative shape is retained unless the book gives
        // a stronger alternative. No fabricated full names or dictionary gender for -а/-я.
        options.maxWithOrNull(compareBy<String> { capitalized[it] ?: 0 }.thenBy { it == low }) ?: low
    }

    private fun possessive(k: String): Boolean {
        val m = POSSESSIVE.matchEntire(k) ?: return false
        val stem = m.groupValues[1]
        val owner = maxOf(capitalized[stem + "а"] ?: 0, capitalized[stem + "я"] ?: 0)
        return owner >= maxOf(5, 3 * (capitalized[k] ?: 0))
    }
    private fun isName(w: String, atStart: Boolean): Boolean {
        val k = key(w)
        if (!w[0].isUpperCase() || (w.length > 1 && w.all { it.isUpperCase() }) || k in NOT_PEOPLE || k in TITLES) return false
        if (possessive(k) || (lower[k] ?: 0) > (inside[k] ?: 0)) return false
        val tagged = k in names
        return if (atStart) (inside[k] ?: 0) > 0 || tagged && (lower[k] ?: 0) == 0
            else tagged || (inside[k] ?: 0) >= 2
    }

    fun extract(book: EpubBook): Extraction {
        statistics(book)
        val whole = run(book, false)
        val collection = detectCollection(book, whole)
        return Extraction(collection, if (collection) run(book, true) else whole)
    }

    private fun run(book: EpubBook, perSection: Boolean): List<Candidate> {
        val candidates = linkedMapOf<Pair<Int, String>, Candidate>()
        fun add(k: String, kind: String, section: Int, text: String, start: Int, end: Int): Candidate {
            val scope = if (perSection) section else 0
            val c = candidates.getOrPut(scope to k) { Candidate(k, kind, scope) }
            c.count++; c.sections.increment(section)
            if (c.examples.size < 3 && (c.examples.isEmpty() || c.count in listOf(5, 40))) {
                val left = maxOf(0, start - 70); val right = minOf(text.length, end + 70)
                c.examples += (if (left > 0) "…" else "") + text.substring(left, start) + "[[" + text.substring(start, end) + "]]" +
                    text.substring(end, right) + if (right < text.length) "…" else ""
            }
            return c
        }
        for (p in book.paragraphs) {
            check()
            val text = p.text
            val words = WORD.findAll(text).toList()
            val mentions = mutableListOf<Mention>()
            var i = 0
            while (i < words.size) {
                var m = words[i]
                var title: String? = null
                val low = key(m.value)
                if (low in TITLES) {
                    title = low
                    val next = words.getOrNull(i + 1)
                    if (next != null && text.substring(m.range.last + 1, next.range.first) == " " && isName(next.value, false)) {
                        i++; m = next
                    } else {
                        val c = add(low, "title", p.section, text, m.range.first, m.range.last + 1)
                        c.forms.increment(m.value); c.genders.increment("Title:${if (low in FEMALE_TITLES) "f" else "m"}")
                        mentions += Mention(m.range.first, m.range.last + 1, c, true)
                        i++; continue
                    }
                }
                if (!isName(m.value, sentenceStart(text, m.range.first))) { i++; continue }
                val chain = mutableListOf(m)
                while (i + 1 < words.size && text.substring(chain.last().range.last + 1, words[i + 1].range.first) == " " && isName(words[i + 1].value, false)) {
                    i++; chain += words[i]
                }
                val original = chain.map { key(it.value) }
                val normalized = chain.map { base(it.value) }
                val family = original.any { w -> Regex(".+(?:овы|евы|ины|ыны|ские|цкие|овых|евых|иных|ыных|ских|цких|овыми|евыми|иными|скими)$").matches(w) }
                val kind = if (family) "family" else "name"
                val c = add((if (family) "семья " else "") + normalized.joinToString(" "), kind, p.section, text, chain.first().range.first, chain.last().range.last + 1)
                c.forms.increment(text.substring(chain.first().range.first, chain.last().range.last + 1))
                normalized.forEach { w ->
                    val info = suffixGender(w)
                    if (info != null) { c.roles.increment(info.second); c.genders.increment("${info.second}:${info.first}") }
                }
                title?.let { c.titles.increment(it); c.genders.increment("Title:${if (it in FEMALE_TITLES) "f" else "m"}") }
                mentions += Mention(chain.first().range.first, chain.last().range.last + 1, c, original == normalized && !family)
                i++
            }
            attributeSpeakers(text, mentions)
            val present = mentions.map { it.candidate }.distinctBy { it.key }
            for (a in present) for (b in present) if (a !== b) a.together.increment(b.key)
        }
        return candidates.values.toList()
    }

    private fun attributeSpeakers(text: String, mentions: List<Mention>) {
        if (!text.startsWith('—') && !text.startsWith('–')) return
        var offset = 1
        DASH.split(text.substring(1)).forEachIndexed { index, part ->
            val start = text.indexOf(part, offset); if (start < 0) return
            val end = start + part.length; offset = end
            if (index % 2 == 0) return@forEachIndexed
            val speaker = mentions.firstOrNull { it.start in start until end && it.nominative } ?: return@forEachIndexed
            // Only lowercase words in the remark can be verbs; names like «Михаил» are not verbs.
            // Require a verb-shaped suffix, length >=4, before the named subject.
            val past = WORD.findAll(part).firstOrNull { w -> w.value.length >= 4 && w.value[0].isLowerCase() &&
                w.range.first < speaker.start - start && PAST.matches(w.value) } ?: return@forEachIndexed
            speaker.candidate.speaker++
            speaker.candidate.genders.increment("verb:${if (past.value.endsWith("ла") || past.value.endsWith("лась")) "f" else "m"}")
        }
    }

    companion object {
        val WORD = Regex("[А-ЯЁа-яё]+(?:-[А-ЯЁа-яё]+)*")
        val TITLES = setOf("князь", "княгиня", "княжна", "граф", "графиня", "барон", "баронесса", "генерал", "генеральша",
            "полковник", "капитан", "поручик", "подпоручик", "майор", "господин", "госпожа", "мадам", "мадемуазель", "мсье",
            "сударь", "сударыня", "барыня", "барин", "доктор", "профессор", "чиновник", "купец", "купчиха")
        private val FEMALE_TITLES = setOf("княгиня", "княжна", "графиня", "баронесса", "генеральша", "госпожа", "мадам", "мадемуазель", "сударыня", "барыня", "купчиха")
        private val NOT_PEOPLE = setOf("бог", "господь", "господи", "христос", "богородица", "аллах", "сатана", "иисус")
        private val ENDINGS = listOf("иями", "ыми", "ими", "ами", "ого", "ому", "ему", "его", "ием", "ией", "еем", "ою", "ею", "ея", "ее", "ия", "ию", "ии", "ий", "ый", "ой", "ую", "ая", "ей", "ым", "им", "ом", "ем", "ых", "их", "а", "у", "е", "ы", "и", "ю", "я")
        private val VARIANTS = listOf("", "о", "а", "я", "ь", "ий", "ей", "ый", "ой", "ая")
        private val POSSESSIVE = Regex("^(.{2,}?)[иы]н(?:а|о|ы|ой|ою|ому|ым|ом|ых|ыми|у|е)?$")
        private val PAST = Regex(".+(?:л|ла|лся|лась)")
        private val DASH = Regex("\\s[—–]\\s")
        fun key(w: String) = w.lowercase().replace('ё', 'е')
        private fun sentenceStart(text: String, start: Int): Boolean {
            var i = start - 1
            while (i >= 0 && text[i] in " \t«\"„“(—–-'") i--
            return i < 0 || text[i] in ".!?…"
        }
        private fun suffixGender(w: String): Pair<String, String>? = when {
            w.endsWithAny("овна", "евна", "ична", "инична") -> "f" to "Patr"
            w.endsWithAny("ович", "евич", "ич", "ыч") -> "m" to "Patr"
            w.endsWithAny("ова", "ева", "ина", "ына", "ская", "цкая") -> "f" to "Surn"
            w.endsWithAny("ов", "ев", "ин", "ын", "ский", "цкий") -> "m" to "Surn"
            else -> null
        }
        private fun String.endsWithAny(vararg suffixes: String) = suffixes.any(::endsWith)
        fun detectCollection(book: EpubBook, candidates: List<Candidate>): Boolean {
            if (book.sections.size < 3) return false
            val named = candidates.filter { it.kind == "name" }.sortedByDescending { it.count }.take(30)
            if (named.isEmpty()) return false
            return named.count { it.sections.size >= maxOf(2, book.sections.size / 4) }.toDouble() / named.size < 0.3
        }
        fun Map<String, Int>.ranked() = entries.sortedByDescending { it.value }
        private fun <T> MutableMap<T, Int>.increment(k: T) { this[k] = (this[k] ?: 0) + 1 }
    }
}
