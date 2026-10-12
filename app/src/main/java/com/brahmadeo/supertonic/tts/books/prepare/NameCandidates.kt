package com.brahmadeo.supertonic.tts.books.prepare

/** Mechanical candidate extraction (port of the extractor in tools/characters/book_characters.py): names,
 * surnames, patronymics, nicknames and titles from this book's own statistics, the bundled names lexicon and the
 * small dictionary [BookMorph]. No character lists for particular books. */
class NameCandidates(private val names: Set<String>, private val morph: BookMorph = BookMorph.EMPTY,
                     private val check: () -> Unit = {}) {
    data class Candidate(val key: String, val kind: String, val scope: Int) {
        var id = ""
        var count = 0
        var speaker = 0
        /** «черномазый», «лакей»: the speaker of a remark named by a description. */
        var descriptor = false
        val genders = linkedMapOf<String, Int>()
        val roles = linkedMapOf<String, Int>()
        val titles = linkedMapOf<String, Int>()
        val forms = linkedMapOf<String, Int>()
        val sections = linkedMapOf<Int, Int>()
        val speakerSections = linkedMapOf<Int, Int>()
        val examples = mutableListOf<String>()
        /** (paragraph, start, end) of every mention. */
        val spots = mutableListOf<Triple<Int, Int, Int>>()
        val together = linkedMapOf<String, Int>()
        /** Passages for «who is this» (a title or a bare surname) and the section id of each. */
        var contexts: List<String> = emptyList()
        var contextSections: List<String> = emptyList()
        /** Mentions and remarks by section id (filled when the plan is built). */
        var sectionCounts: Map<String, Int> = emptyMap()
        var speakerCounts: Map<String, Int> = emptyMap()
        val display get() = forms.ranked().firstOrNull()?.key ?: key
        /** Gender and its source: a remark verb («сказала») is the most reliable, then patronymic, title, first
         * name, surname («Ганя» is feminine by the dictionary, but «— сказал Ганя»). */
        fun genderSource(): Pair<String, String?> {
            for (source in listOf("verb", "Patr", "Title", "Name", "Surn")) {
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
    /** A reading of a word as a first name, patronymic or surname. */
    data class Named(val lemma: String, val case: String, val gender: String, val number: String, val role: String)
    private class Mention(val start: Int, val end: Int, val candidate: Candidate, val nominative: Boolean)
    private class Chain(val key: String, val info: List<Pair<String?, String?>>, val family: Boolean, val nominative: Boolean)

    private val capitalized = hashMapOf<String, Int>()
    private val lower = hashMapOf<String, Int>()
    private val inside = hashMapOf<String, Int>()
    private val normalInside = hashMapOf<String, Int>()
    private val namedCache = hashMapOf<String, List<Named>>()

    fun statistics(book: EpubBook) {
        capitalized.clear(); lower.clear(); inside.clear(); normalInside.clear()
        for (p in book.paragraphs) {
            check()
            for (m in WORD.findAll(p.text)) {
                val w = m.value; val k = key(w)
                if (w[0].isLowerCase()) lower.increment(k) else {
                    capitalized.increment(k)
                    if (!sentenceStart(p.text, m.range.first)) {
                        inside.increment(k)
                        normalInside.increment(named(w).firstOrNull()?.lemma ?: k)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ morphology of names

    /** First names from the dictionary; patronymics and surnames by their regular endings. */
    fun named(word: String): List<Named> = namedCache.getOrPut(key(word)) {
        val k = key(word)
        val out = mutableListOf<Named>()
        for (p in morph.name(k)) out += Named(p.lemma, p.case, if (p.gender == "m" || p.gender == "f") p.gender else "", "sing", "Name")
        for ((ending, forms) in PATRONYMIC) if (k.endsWith(ending) && k.length - ending.length >= 3) {
            val stem = k.dropLast(ending.length)
            for ((lemma, case, gender) in forms) out += Named(stem + lemma, case, gender, "sing", "Patr")
            break
        }
        if (out.none { it.role == "Patr" }) for ((ending, forms) in SURNAME) if (k.endsWith(ending) && k.length - ending.length >= 2) {
            val stem = k.dropLast(ending.length)
            for ((lemma, case, gender, number) in forms) out += Named(stem + lemma, case, gender, number, "Surn")
            break
        }
        out
    }
    private fun dictionary(word: String) = morph.name(word).isNotEmpty() || key(word) in names
    private fun Named.single() = number == "sing"

    // ------------------------------------------------------------ what is a name

    private fun possessive(k: String): Boolean {
        val m = POSSESSIVE.matchEntire(k) ?: return false
        val stem = m.groupValues[1]
        val owner = maxOf(capitalized[stem + "а"] ?: 0, capitalized[stem + "я"] ?: 0)
        return owner >= maxOf(5, 3 * (capitalized[k] ?: 0))
    }

    fun isName(w: String, atStart: Boolean): Boolean {
        val k = key(w)
        if (!w[0].isUpperCase() || (w.length > 1 && w.all { it.isUpperCase() }) || k in NOT_PEOPLE || k in TITLES || morph.title(k).isNotEmpty()) return false
        // «Фу», «Ай», «Ну», «Он»: an interjection, particle or pronoun is not a name.
        if (morph.function(k) != null && morph.name(k).isEmpty()) return false
        val tagged = named(w).isNotEmpty() || k in names
        // «Москва» from the dictionary is not a person, unless the book itself uses it as a name.
        if (morph.geo(k)) return false
        if (possessive(k)) return false // «Зинина», «Лидиной»: somebody's
        val lowerCount = lower[k] ?: 0; val insideCount = inside[k] ?: 0
        if (lowerCount > insideCount) return false // a common word, just at a sentence start
        if (atStart) return insideCount > 0 || (dictionary(w) && lowerCount == 0) ||
            ((normalInside[named(w).firstOrNull()?.lemma ?: k] ?: 0) >= 2 && lowerCount == 0)
        // «Медуза» is a nickname: the book has «медуза» too, but more often capitalized inside a sentence.
        return tagged || insideCount >= 2 || (insideCount >= 1 && lowerCount == 0)
    }

    /** Book form for a word the dictionary does not know: «Рогожина» → «рогожин», «Кирюху» → «кирюха». Every
     * returned base is present with a capital in this book. */
    fun base(word: String): String = unknownBase(key(word), null)

    private fun unknownBase(low: String, expected: String?): String {
        fun allowed(base: String): Boolean {
            val parses = named(base)
            return expected == null || parses.isEmpty() || parses.any { it.gender.isEmpty() || it.gender == expected }
        }
        fun cap(x: String) = capitalized[x] ?: 0
        // Unknown surnames still inherit the explicit gender of a title/patronymic.
        if (expected == "f" && low.endsWith("ой")) {
            for (base in listOf(low.dropLast(2) + "ая", low.dropLast(2) + "а")) if (allowed(base) && cap(base) > 0) return base
        }
        // Adjectival surnames: «Тоцким» → «Тоцкий», only when that nominative occurs in the same text.
        for (ending in listOf("ого", "ому", "ыми", "ых", "им", "ым", "ом")) if (low.endsWith(ending)) {
            val stem = low.dropLast(ending.length)
            for (suffix in listOf("ий", "ый", "ой")) {
                val base = stem + suffix
                if (stem.length >= 3 && allowed(base) && cap(base) >= maxOf(2, cap(low) / 4)) return base
            }
        }
        // The most frequent base in the book: «Кирюху» → «Кирюха» (48), not the vocative «Кирюх» (2).
        val bases = mutableListOf<String>()
        for (ending in CASE_ENDINGS) if (low.endsWith(ending) && low.length - ending.length >= 3) {
            val stem = low.dropLast(ending.length)
            for (base in listOf(stem, stem + "о", stem + "а", stem + "я", stem + "ь")) {
                if (base != low && allowed(base) && cap(base) >= maxOf(2, cap(low) / 4)) {
                    // «Кирюха» may itself be a nominative: to «Кирюх» only when that form is more frequent.
                    if ((ending == "а" || ending == "я") && base == stem && cap(base) <= cap(low)) continue
                    bases += base
                }
            }
        }
        return bases.maxByOrNull { cap(it) } ?: low
    }

    /** «Евгения Павловича» → «евгений павлович»: case and gender agree across the words. */
    private fun chainKey(words: List<String>, expectedGender: String?, expectedCase: String?): Chain {
        fun cap(x: String) = capitalized[x] ?: 0
        var parsed = words.map { named(it) }
        if (expectedCase != null) parsed = parsed.map { opt -> opt.filter { it.case == expectedCase }.ifEmpty { opt } }
        var options = parsed.map { opt -> opt.filter { it.single() } }
        if (expectedGender != null) options = options.map { opt -> opt.filter { it.gender == expectedGender }.ifEmpty { opt } }
        // A surname seen only as a plural is a family («Бобриковых»), unless it has the singular surname shape.
        val surnameNom = words.indices.map { i ->
            val all = named(words[i])
            all.isNotEmpty() && options[i].isEmpty() && (SINGLE_SURNAME.matches(key(words[i])) || all.none { it.role == "Surn" })
        }
        val family = words.indices.any { i ->
            val all = named(words[i])
            (all.isNotEmpty() && options[i].isEmpty() && !surnameNom[i]) || (all.isEmpty() && FAMILY_SURNAME.containsMatchIn(key(words[i])))
        }
        var shared: Set<Pair<String, String>>? = null
        for (opt in options) if (opt.isNotEmpty()) {
            val cases = opt.map { it.case to it.gender }.toSet()
            shared = shared?.intersect(cases) ?: cases
        }
        val genders = shared.orEmpty().map { it.second }.filter { it == "m" || it == "f" }.toSet()
        val agreedGender = expectedGender ?: genders.singleOrNull()
        val keys = mutableListOf<String>()
        val info = mutableListOf<Pair<String?, String?>>()
        var nominative = true
        for ((i, w) in words.withIndex()) {
            val low = key(w)
            var opt = options[i]
            if (surnameNom[i]) {
                keys += low
                val surname = named(w).any { it.role == "Surn" }
                info += (if (surname) "m" else null) to (if (surname) "Surn" else "Name")
                continue
            }
            if (opt.isEmpty() && named(w).isEmpty() && FAMILY_SURNAME.containsMatchIn(low)) {
                keys += FAMILY_SURNAME.replace(low) { it.groupValues[1] }
                info += null to "Surn"; nominative = false
                continue
            }
            if (opt.isEmpty()) {
                val parses = named(w)
                if (parses.isNotEmpty()) { keys += parses[0].lemma; info += null to parses[0].role }
                else {
                    var hint = agreedGender
                    if (hint == null && low.endsWith("ой") && SURNAME_FEMININE.containsMatchIn(low)) hint = "f"
                    keys += unknownBase(low, hint)
                    info += null to null
                    nominative = nominative && keys.last() == low
                }
                continue
            }
            if (shared != null) opt = opt.filter { (it.case to it.gender) in shared!! }.ifEmpty { opt }
            val forms = linkedMapOf<String, MutableList<Named>>()
            for (p in opt) forms.getOrPut(p.lemma) { mutableListOf() } += p
            val fallback = unknownBase(low, agreedGender)
            // The written form is the key only when it looks like the nominative of a diminutive («Владя»), not
            // «Афанасием» or «Ивановича».
            val asWritten = fallback == low && cap(low) >= 3 && opt.all { it.role == "Name" } &&
                (low.endsWith("а") || low.endsWith("я") || (forms.keys.all { cap(it) == 0 } &&
                    opt.flatMap { morph.lexeme(it.lemma) }.none { it != low && cap(it) > 0 }))
            // «Кэле», «Мити»: an indeclinable foreign name; the dictionary sees a case that the book never uses.
            if ((fallback != low || asWritten) && fallback !in forms && forms.keys.all { cap(it) == 0 } && cap(fallback) > 0)
                forms[fallback] = forms.getValue(forms.keys.maxByOrNull { cap(it) }!!)
            if (fallback != low && fallback !in forms && cap(fallback) >= 5 * forms.keys.maxOf { cap(it) } && opt.all { it.role == "Name" })
                forms[fallback] = forms.getValue(forms.keys.maxByOrNull { cap(it) }!!)
            val chosenKey = forms.keys.maxWithOrNull(compareBy<String> { cap(it) }.thenBy { it == low })!!
            val chosen = forms.getValue(chosenKey)[0]
            keys += chosenKey
            val indeclinable = chosenKey == low && asWritten && !low.endsWith("а") && !low.endsWith("я")
            nominative = nominative && (indeclinable || forms.getValue(chosenKey).any { it.case == "nomn" })
            info += chosen.gender.takeIf { it == "m" || it == "f" } to chosen.role
        }
        return Chain(keys.joinToString(" "), info, family, nominative)
    }

    /** Adjacent names must agree; an addressee and a speaker are not one long name. */
    private fun canContinue(words: List<String>, following: String): Boolean {
        fun options(word: String) = named(word).filter { it.single() }
        val previous = words.map { options(it) }
        val next = options(following)
        var shared: Set<Pair<String, String>>? = null
        for (opt in previous) if (opt.isNotEmpty()) {
            val values = opt.map { it.case to it.gender }.toSet()
            shared = shared?.intersect(values) ?: values
        }
        if (shared != null && shared.isNotEmpty() && next.isNotEmpty() && shared.intersect(next.map { it.case to it.gender }.toSet()).isEmpty()) return false
        // Only regular surname shapes: an unknown foreign word («Сяопин», «Мэннинг») may well be part of one name.
        fun surnameLike(word: String) = named(word).let { p -> p.isNotEmpty() && p.all { it.role == "Surn" } }
        if (surnameLike(words.last()) && surnameLike(following)) return false // «Рогожин Лебедеву»: two people
        if (previous.any { opt -> opt.any { it.role == "Patr" } }) {
            val roles = next.map { it.role }.toSet()
            if ("Name" in roles && "Surn" !in roles) return false
        }
        return true
    }

    /** «князь Мышкин» is one person; «сказал князь Рогожину» — a title and an addressee in another case. */
    private fun titleAgrees(title: BookMorph.Parse, word: String): Boolean {
        val cases = named(word).map { it.case }.toSet()
        return cases.isEmpty() || title.case in cases || cases.none { it in setOf("nomn", "gent", "datv", "accs", "ablt", "loct") }
    }

    // ------------------------------------------------------------ extraction

    fun extract(book: EpubBook): Extraction {
        val text = BookStructure.normalized(book)
        val whole = run(text, false)
        val collection = BookStructure.collection(book, whole)
        return Extraction(collection, if (collection) run(text, true) else whole)
    }

    fun run(book: EpubBook, perSection: Boolean): List<Candidate> {
        statistics(book)
        val candidates = linkedMapOf<Pair<Int, String>, Candidate>()
        var paragraph = -1
        fun add(k: String, kind: String, section: Int, text: String, start: Int, end: Int): Candidate {
            val scope = if (perSection) section else 0
            val c = candidates.getOrPut(scope to k) { Candidate(k, kind, scope) }
            c.count++; c.sections.increment(section)
            if (c.examples.size < 6 && (c.count <= 3 || c.count in listOf(10, 40, 100))) c.examples += snippet(text, start, end)
            c.spots += Triple(paragraph, start, end)
            return c
        }
        for (p in book.paragraphs) {
            check()
            paragraph++
            val section = p.section
            val text = p.text
            val words = WORD.findAll(text).toList()
            val mentions = mutableListOf<Mention>()
            var i = 0
            while (i < words.size) {
                var m = words[i]
                val low = key(m.value)
                var title: BookMorph.Parse? = null
                val titleParses = morph.title(low).ifEmpty { if (low in TITLES) listOf(BookMorph.Parse(low, "nomn", if (low in FEMALE_TITLES) "f" else "m")) else emptyList() }
                if (titleParses.isNotEmpty()) {
                    val single = titleParses.filter { it.number != "plur" }
                    if (single.isEmpty()) { i++; continue } // «господа», «князей Мышкиных»: not one person
                    val parsed = single.firstOrNull { it.case == "nomn" } ?: single[0]
                    title = parsed
                    val next = words.getOrNull(i + 1)
                    if (next != null && text.substring(m.range.last + 1, next.range.first) == " " && isName(next.value, false) && titleAgrees(parsed, next.value)) {
                        i++; m = next // a title before a name: «генерал Иволгин»
                    } else {
                        val c = add(parsed.lemma, "title", section, text, m.range.first, m.range.last + 1)
                        c.forms.increment(m.value)
                        if (parsed.gender == "m" || parsed.gender == "f") c.genders.increment("Title:${parsed.gender}")
                        mentions += Mention(m.range.first, m.range.last + 1, c, parsed.case == "nomn")
                        i++; continue
                    }
                }
                if (!isName(m.value, sentenceStart(text, m.range.first))) { i++; continue }
                val chain = mutableListOf(m)
                while (i + 1 < words.size && text.substring(chain.last().range.last + 1, words[i + 1].range.first) == " " && isName(words[i + 1].value, false)) {
                    if (!canContinue(chain.map { it.value }, words[i + 1].value)) break
                    i++; chain += words[i]
                }
                var expectedCase = title?.case
                val firstIndex = i - chain.size + 1
                if (title == null && firstIndex > 0 && text.substring(words[firstIndex - 1].range.last + 1, chain[0].range.first) == " ")
                    expectedCase = PREPOSITION_CASE[key(words[firstIndex - 1].value)]
                val gender = title?.gender?.takeIf { it == "m" || it == "f" }
                val k = chainKey(chain.map { it.value }, gender, expectedCase)
                val c = add(if (k.family) "семья ${k.key}" else k.key, if (k.family) "family" else "name", section, text,
                    chain.first().range.first, chain.last().range.last + 1)
                c.forms.increment(text.substring(chain.first().range.first, chain.last().range.last + 1))
                for ((g, role) in k.info) if (role != null) {
                    c.roles.increment(role)
                    if (g != null) c.genders.increment("$role:$g")
                }
                if (title != null) {
                    c.titles.increment(title.lemma)
                    if (gender != null) c.genders.increment("Title:$gender")
                }
                mentions += Mention(chain.first().range.first, chain.last().range.last + 1, c, k.nominative)
                i++
            }
            // Apposition also identifies titles: «Иван Петрович, отставной генерал».
            for (mention in mentions) {
                if (mention.candidate.kind != "title") continue
                val name = mentions.lastOrNull { it.candidate.kind == "name" && it.end < mention.start } ?: continue
                val gap = text.substring(name.end, mention.start)
                if (gap.length <= 64 && APPOSITION_GAP.matches(gap)) {
                    val qualifiers = WORD.findAll(gap).map { it.value.lowercase() }.toList()
                    if (qualifiers.isNotEmpty() && qualifiers.all { adjective(it) || morph.adverb(it) } && qualifiers.any { adjective(it) })
                        name.candidate.titles.increment(mention.candidate.key)
                }
            }
            attributeSpeakers(text, section, mentions) { k, start, end, g ->
                val c = add(k, "title", section, text, start, end)
                c.descriptor = true
                c.forms.increment(text.substring(start, end))
                c.speaker++; c.speakerSections.increment(section)
                c.genders.increment("verb:$g")
            }
            val present = mentions.map { it.candidate }.distinct()
            for (a in present) for (b in present) if (a !== b) a.together.increment(b.key)
        }
        return candidates.values.toList()
    }

    // ------------------------------------------------------------ remarks

    /** «— Реплика, — сказал князь. — Ещё реплика»: remarks are the odd parts between dashes. [describe] — the
     * speaker is named by a description, not a name: «— спросил черномазый», «— промычал лакей». */
    private fun attributeSpeakers(text: String, section: Int, mentions: List<Mention>, describe: (String, Int, Int, String) -> Unit) {
        if (!text.startsWith('—') && !text.startsWith('–')) return
        var offset = 1
        DASH.split(text.substring(1)).forEachIndexed { index, part ->
            val start = text.indexOf(part, offset); if (start < 0) return
            val end = start + part.length; offset = end
            if (index % 2 == 0) return@forEachIndexed
            // Only a nearby subject and a speech verb in the opening clause count.
            val clause = CLAUSE_END.split(part, 2)[0].take(180)
            val inside = mentions.filter { it.start >= start && it.start < start + clause.length && it.end <= start + clause.length &&
                it.nominative && it.candidate.kind != "family" }
            val verbs = WORD.findAll(clause).mapNotNull { w -> speechVerb(w.value)?.let { w to it } }.toList()
            if (inside.isEmpty()) {
                if (verbs.isNotEmpty()) describeSpeaker(clause, start, verbs[0], describe)
                return@forEachIndexed
            }
            data class Pair4(val comma: Boolean, val gap: Int, val mention: Mention, val gender: String)
            val pairs = mutableListOf<Pair4>()
            for ((w, g) in verbs) for (mention in inside) {
                val a = (mention.start - start) to (mention.end - start)
                val b = w.range.first to (w.range.last + 1)
                val (left, right) = if (a.first <= b.first) a to b else b to a
                if (left.second > right.first) continue
                val gap = clause.substring(left.second, right.first)
                if (gap.length > 48 || GAP_STOP.containsMatchIn(gap)) continue
                if (',' in gap && !parenthetical(gap, mention.start - start > w.range.first)) continue
                if (inside.any { it !== mention && it.start - start >= left.second && it.start - start < right.first }) continue
                pairs += Pair4(',' in gap, gap.length, mention, g)
            }
            if (pairs.isEmpty()) soleSpeaker(clause, start, inside, verbs)?.let { pairs += Pair4(true, it.second, it.first, it.third) }
            val best = pairs.minWithOrNull(compareBy<Pair4> { it.comma }.thenBy { it.gap }) ?: return@forEachIndexed
            val speaker = best.mention.candidate
            speaker.speaker++
            speaker.speakerSections.increment(section)
            if (best.gender == "m" || best.gender == "f") speaker.genders.increment("verb:${best.gender}")
        }
    }

    /** A remark verb: past tense («сказал», «спросила») — gender m/f, or "" for plural/neuter — or present tense
     * third person singular («говорит», «кричит»: modern and translated prose). Null — not such a verb. */
    fun speechVerb(word: String): String? {
        if (word.length < 4 || !word[0].isLowerCase()) return null
        val k = key(word)
        if (morph.function(k) != null || morph.adverb(k) || morph.person(k) != null || adjective(k) || k in TITLES) return null
        return when {
            PAST_M.matches(k) -> "m"
            PAST_F.matches(k) -> "f"
            PAST_OTHER.matches(k) -> ""
            k.length >= 5 && PRESENT.matches(k) -> ""
            else -> null
        }
    }

    /** One person is named in the remark, the gender of the first verb agrees with them and no other verb stands
     * between them: «— завороженно сказал ещё красный и мокрый от слёз … Павлуша». Otherwise the speaker is unknown. */
    private fun soleSpeaker(clause: String, start: Int, inside: List<Mention>, verbs: List<Pair<MatchResult, String>>): Triple<Mention, Int, String>? {
        val people = inside.distinctBy { System.identityHashCode(it.candidate) }
        if (people.size != 1 || verbs.isEmpty()) return null
        val mention = people[0]
        val (w, g) = verbs[0]
        val a = (mention.start - start) to (mention.end - start)
        val b = w.range.first to (w.range.last + 1)
        val (left, right) = if (a.first <= b.first) a to b else b to a
        if (right.first - left.second > 150 || verbs.drop(1).any { (x, _) -> x.range.first >= left.second && x.range.first < right.first }) return null
        val last = clause.substring(a.first, a.second).split(' ').last()
        val genders = named(last).map { it.gender }.filter { it.isNotEmpty() }.toSet()
        if ((g == "m" || g == "f") && genders.isNotEmpty() && g !in genders) return null
        return Triple(mention, right.first - left.second, g)
    }

    /** Between a verb and the speaker only a parenthetical in commas: an adverb, particle or gerund
     * («— бормотала, улыбаясь, баба Катя»); before the name itself one more noun may stand («баба Катя»). */
    private fun parenthetical(gap: String, nameAfterVerb: Boolean): Boolean {
        var words = WORD.findAll(gap).toList()
        if (nameAfterVerb && words.isNotEmpty() && ',' !in gap.substring(words.last().range.last + 1)) {
            if (morph.person(words.last().value) != null) words = words.dropLast(1)
        }
        return words.all { w ->
            val k = key(w.value)
            val f = morph.function(k).orEmpty()
            morph.adverb(k) || "PRCL" in f || "CONJ" in f || gerund(k)
        }
    }

    /** Right after the speech verb: a person noun or a substantivized adjective in the nominative
     * («— спросил черномазый», «— промычал удивленный лакей»): such a speaker is a candidate too. */
    private fun describeSpeaker(clause: String, start: Int, verb: Pair<MatchResult, String>, describe: (String, Int, Int, String) -> Unit) {
        val (w, g) = verb
        if (g != "m" && g != "f") return
        val rest = WORD.findAll(clause, w.range.last + 1).toList()
        for ((n, x) in rest.take(4).withIndex()) {
            val before = clause.substring(if (n > 0) rest[n - 1].range.last + 1 else w.range.last + 1, x.range.first)
            if (before.any { it == ',' || it == '—' || it == '–' }) return
            val low = key(x.value)
            if (x.value[0].isUpperCase() || low in TITLES || morph.title(low).isNotEmpty()) return // already a mention
            val f = morph.function(low).orEmpty()
            if ("NPRO" in f && low !in NOMINATIVE_PRONOUNS) continue // «— отвечал ему собеседник»
            val person = morph.person(low)
            // A masculine profession for a woman («— спросила орнитолог»): the gender comes from the verb.
            if (person != null && (g in person || "m" in person)) { describe(low, start + x.range.first, start + x.range.last + 1, g); return }
            if (low in PRONOUN_ADJECTIVES || low in NOT_DESCRIPTORS || NUMERAL.matches(low)) return // «— сказал другой»
            if (adjectiveNominative(low) == g) {
                if (n + 1 == rest.size || clause.substring(x.range.last + 1, rest[n + 1].range.first).isNotBlank()) {
                    describe(low, start + x.range.first, start + x.range.last + 1, g); return
                }
                continue
            }
            if (!(morph.adverb(low) || "PRCL" in f)) return
        }
    }

    private fun adjective(k: String) = ADJECTIVE.matches(k) && morph.person(k) == null && morph.function(k) == null
    /** Gender of a nominative singular adjective or participle («удивленный» → m), or null. */
    private fun adjectiveNominative(k: String): String? = when {
        !adjective(k) -> null
        ADJECTIVE_M.matches(k) -> "m"
        ADJECTIVE_F.matches(k) -> "f"
        else -> null
    }
    private fun gerund(k: String) = GERUND.matches(k) && morph.person(k) == null

    companion object {
        val WORD = Regex("[А-ЯЁа-яё]+(?:-[А-ЯЁа-яё]+)*")
        val TITLES = setOf("князь", "княгиня", "княжна", "граф", "графиня", "барон", "баронесса", "генерал", "генеральша",
            "полковник", "капитан", "поручик", "подпоручик", "майор", "господин", "госпожа", "мадам", "мадемуазель", "мсье",
            "сударь", "сударыня", "барыня", "барин", "доктор", "профессор", "чиновник", "купец", "купчиха",
            "леди", "лорд", "сэр", "мистер", "миссис", "мисс", "фрау", "герр", "сеньор", "сеньора", "синьор", "синьора",
            "пан", "пани", "месье", "миледи", "милорд")
        private val FEMALE_TITLES = setOf("княгиня", "княжна", "графиня", "баронесса", "генеральша", "госпожа", "мадам", "мадемуазель",
            "сударыня", "барыня", "купчиха", "леди", "миссис", "мисс", "фрау", "сеньора", "синьора", "пани", "миледи")
        private val NOT_PEOPLE = setOf("бог", "господь", "господи", "христос", "богородица", "аллах", "сатана", "иисус")
        private val NOT_DESCRIPTORS = setOf("другой", "первый", "второй", "последний", "остальной", "один", "оба", "голос", "кто", "никто", "всякий",
            "другая", "первая", "вторая", "последняя", "одна", "обе")
        private val PRONOUN_ADJECTIVES = setOf("тот", "та", "этот", "эта", "такой", "такая", "сам", "сама", "самый", "самая", "весь", "вся",
            "каждый", "каждая", "который", "которая", "какой", "какая", "чей", "чья", "мой", "моя", "твой", "твоя", "свой", "своя", "наш",
            "наша", "ваш", "ваша", "его", "ее", "их", "некий", "некая", "иной", "иная", "любой", "любая", "никакой", "никакая")
        private val NOMINATIVE_PRONOUNS = setOf("я", "ты", "он", "она", "оно", "мы", "вы", "они", "кто", "что", "никто", "некто", "кое-кто")
        private val NUMERAL = Regex("(?:один|одна|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять)")
        private val CASE_ENDINGS = listOf("ами", "ями", "ому", "ему", "ыми", "ими", "ою", "ею", "ой", "ей", "ым", "им", "ом", "ем", "ых", "их",
            "а", "у", "е", "ы", "и", "ю", "я")
        private val POSSESSIVE = Regex("^(.{2,}?)[иы]н(?:а|о|ы|ой|ою|ому|ым|ом|ых|ыми|у|е)?$")
        private val SINGLE_SURNAME = Regex("^[а-яе-]{3,}(?:ов|ев|ин|ын)$")
        private val FAMILY_SURNAME = Regex("(?<=[а-яе]{2})(ов|ев|ин|ын)(?:ы|ых|ыми)$")
        private val SURNAME_FEMININE = Regex("(?:ов|ев|ин|ын|ск|цк)ой$")
        private val PAST_M = Regex(".+(?:л|лся)")
        private val PAST_F = Regex(".+(?:ла|лась)")
        private val PAST_OTHER = Regex(".+(?:ло|ли|лось|лись)")
        private val PRESENT = Regex(".+(?:ет|ит|ется|ится)")
        private val ADJECTIVE = Regex(".{2,}(?:ый|ий|ой|ая|яя|ое|ее|ые|ие|ого|его|ому|ему|ым|им|ом|ем|ую|юю|ых|их|ыми|ими)")
        private val ADJECTIVE_M = Regex(".{2,}(?:ый|ий|ой)")
        private val ADJECTIVE_F = Regex(".{2,}(?:ая|яя)")
        private val GERUND = Regex(".{2,}(?:ясь|вшись|вши|учи|ючи)|.{3,}я|.{3,}[жшчщ]а|.{3,}[аеиоуыя]в")
        private val DASH = Regex("\\s[—–]\\s")
        private val CLAUSE_END = Regex("[.!?…;:(]")
        private val GAP_STOP = Regex("[.:;!?…]")
        private val APPOSITION_GAP = Regex("\\s*,\\s*(?:[А-ЯЁа-яё-]+\\s+){0,3}")
        private val PREPOSITION_CASE = mapOf("к" to "datv", "ко" to "datv", "от" to "gent", "из" to "gent", "без" to "gent",
            "для" to "gent", "у" to "gent", "около" to "gent", "возле" to "gent", "до" to "gent",
            "над" to "ablt", "перед" to "ablt", "между" to "ablt")
        /** Regular patronymic forms: ending → (lemma ending, case, gender). */
        private val PATRONYMIC: List<Pair<String, List<Triple<String, String, String>>>> = run {
            val out = mutableListOf<Pair<String, List<Triple<String, String, String>>>>()
            for (m in listOf("ович", "евич", "ьич")) {
                out += m + "ем" to listOf(Triple(m, "ablt", "m"))
                out += m + "а" to listOf(Triple(m, "gent", "m"), Triple(m, "accs", "m"))
                out += m + "у" to listOf(Triple(m, "datv", "m"))
                out += m + "е" to listOf(Triple(m, "loct", "m"))
                out += m to listOf(Triple(m, "nomn", "m"))
            }
            for (f in listOf("овна", "евна", "ична", "инична")) {
                val s = f.dropLast(1)
                out += s + "ой" to listOf(Triple(f, "gent", "f"), Triple(f, "datv", "f"), Triple(f, "ablt", "f"), Triple(f, "loct", "f"))
                out += s + "ою" to listOf(Triple(f, "ablt", "f"))
                out += s + "ы" to listOf(Triple(f, "gent", "f"))
                out += s + "е" to listOf(Triple(f, "datv", "f"), Triple(f, "loct", "f"))
                out += s + "у" to listOf(Triple(f, "accs", "f"))
                out += f to listOf(Triple(f, "nomn", "f"))
            }
            out.sortedByDescending { it.first.length }
        }
        private data class Form(val lemma: String, val case: String, val gender: String, val number: String)
        /** Regular surname forms (Ivanov / Ivanova / Ivanovy, Tolstoy-type adjectival): ending → readings. */
        private val SURNAME: List<Pair<String, List<Form>>> = run {
            val out = mutableListOf<Pair<String, List<Form>>>()
            for (t in listOf("ов", "ев", "ин", "ын")) {
                out += t + "ыми" to listOf(Form(t, "ablt", "", "plur"))
                out += t + "ых" to listOf(Form(t, "gent", "", "plur"), Form(t, "accs", "", "plur"), Form(t, "loct", "", "plur"))
                out += t + "ым" to listOf(Form(t, "ablt", "m", "sing"), Form(t, "datv", "", "plur"))
                out += t + "ой" to listOf(Form(t + "а", "gent", "f", "sing"), Form(t + "а", "datv", "f", "sing"), Form(t + "а", "ablt", "f", "sing"), Form(t + "а", "loct", "f", "sing"))
                out += t + "ою" to listOf(Form(t + "а", "ablt", "f", "sing"))
                out += t + "ы" to listOf(Form(t, "nomn", "", "plur"))
                out += t + "а" to listOf(Form(t, "gent", "m", "sing"), Form(t, "accs", "m", "sing"), Form(t + "а", "nomn", "f", "sing"))
                out += t + "у" to listOf(Form(t, "datv", "m", "sing"), Form(t + "а", "accs", "f", "sing"))
                out += t + "е" to listOf(Form(t, "loct", "m", "sing"))
                out += t to listOf(Form(t, "nomn", "m", "sing"))
            }
            for (s in listOf("ск", "цк")) {
                out += s + "ого" to listOf(Form(s + "ий", "gent", "m", "sing"), Form(s + "ий", "accs", "m", "sing"))
                out += s + "ому" to listOf(Form(s + "ий", "datv", "m", "sing"))
                out += s + "ими" to listOf(Form(s + "ий", "ablt", "", "plur"))
                out += s + "им" to listOf(Form(s + "ий", "ablt", "m", "sing"), Form(s + "ий", "datv", "", "plur"))
                out += s + "ом" to listOf(Form(s + "ий", "loct", "m", "sing"))
                out += s + "ий" to listOf(Form(s + "ий", "nomn", "m", "sing"))
                out += s + "ая" to listOf(Form(s + "ая", "nomn", "f", "sing"))
                out += s + "ой" to listOf(Form(s + "ая", "gent", "f", "sing"), Form(s + "ая", "datv", "f", "sing"), Form(s + "ая", "ablt", "f", "sing"), Form(s + "ая", "loct", "f", "sing"))
                out += s + "ую" to listOf(Form(s + "ая", "accs", "f", "sing"))
                out += s + "ою" to listOf(Form(s + "ая", "ablt", "f", "sing"))
                out += s + "ие" to listOf(Form(s + "ий", "nomn", "", "plur"))
                out += s + "их" to listOf(Form(s + "ий", "gent", "", "plur"), Form(s + "ий", "accs", "", "plur"), Form(s + "ий", "loct", "", "plur"))
            }
            out.sortedByDescending { it.first.length }
        }
        fun key(w: String) = w.lowercase().replace('ё', 'е')
        fun snippet(text: String, start: Int, end: Int, width: Int = 70): String {
            val left = maxOf(0, start - width); val right = minOf(text.length, end + width)
            return (if (left > 0) "…" else "") + text.substring(left, start) + "[[" + text.substring(start, end) + "]]" +
                text.substring(end, right) + if (right < text.length) "…" else ""
        }
        fun sentenceStart(text: String, start: Int): Boolean {
            var i = start - 1
            while (i >= 0 && text[i] in " \t«\"„“(—–-'") i--
            return i < 0 || text[i] in ".!?…"
        }
        fun Map<String, Int>.ranked() = entries.sortedByDescending { it.value }
        private fun <T> MutableMap<T, Int>.increment(k: T) { this[k] = (this[k] ?: 0) + 1 }
    }
}
