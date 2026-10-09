package com.brahmadeo.supertonic.tts.books.prepare

import org.json.JSONArray
import org.json.JSONObject

/** Decisions on the LLM answers (port of tools/characters/book_characters.py): votes, merge verification, «who
 * is this» passages for titles and bare surnames, chapter-by-chapter titles. Only verified, unambiguous aliases
 * reach a character; everything else remains in «прочие». */
object CastCheck {
    /** A title or a bare surname stays with a person when at least this share of clear answers names them. */
    const val DOMINANCE = 0.8
    /** A chapter where the main answer already gave the title to this person and no passage names anybody else. */
    const val AGREED_DOMINANCE = 0.6
    /** Fewer clear answers — no decision on the title (it stays in «прочие»). */
    const val MIN_LABEL_ANSWERS = 3
    const val MIN_CHAPTER_ANSWERS = 2
    const val LABEL_BATCH = 40

    enum class Kind { MAIN, VERIFY, LABELS }
    private val GENDERS = mapOf("m" to "m", "м" to "m", "male" to "m", "masc" to "m", "муж" to "m", "мужской" to "m",
        "f" to "f", "ж" to "f", "female" to "f", "femn" to "f", "жен" to "f", "женский" to "f")

    // ------------------------------------------------------------ answers

    /** The JSON object of an answer, minor deviations normalized («м» → «m», a number as a string), or null when it
     * is not a complete answer of this [kind]. Duplicate keys make the answer invalid. */
    fun parse(raw: String, kind: Kind): JSONObject? {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = try { JSONObject(raw.substring(start, end + 1)) } catch (_: Exception) { return null }
        normalize(o)
        return o.takeIf { valid(it, kind) }
    }

    private fun objects(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.opt(it) }

    private fun normalize(o: JSONObject) {
        for (ch in objects(o.optJSONArray("characters"))) if (ch is JSONObject) {
            if (ch.has("gender")) ch.put("gender", GENDERS[ch.opt("gender").toString().trim().lowercase()] ?: "?")
            (ch.opt("id") as? Number)?.let { ch.put("id", it.toString()) }
            if (ch.isNull("name")) ch.put("name", "")
        }
        for (c in objects(o.optJSONArray("checks"))) if (c is JSONObject) (c.opt("verdict") as? String)?.let { c.put("verdict", it.trim().lowercase()) }
        for (a in objects(o.optJSONArray("answers"))) if (a is JSONObject) (a.opt("n") as? String)?.trim()?.toIntOrNull()?.let { a.put("n", it) }
    }

    private fun valid(o: JSONObject, kind: Kind): Boolean = when (kind) {
        Kind.LABELS -> o.optJSONArray("answers")?.let { a -> objects(a).all { it is JSONObject && it.opt("n") is Int && it.opt("who") is String } } ?: false
        Kind.VERIFY -> o.optJSONArray("checks")?.let { a -> objects(a).all { c -> c is JSONObject &&
            listOf("character", "anchor", "candidate").all { c.opt(it) is String } && c.opt("verdict") in listOf("same", "different", "unsure") } } ?: false
        Kind.MAIN -> {
            val characters = o.optJSONArray("characters"); val other = o.optJSONArray("other")
            characters != null && other != null && objects(characters).all { c -> c is JSONObject && c.opt("id") is String &&
                c.opt("name") is String && c.opt("gender") in listOf("m", "f", "?") &&
                (c.optJSONArray("candidates")?.let { r -> objects(r).all { it is String } } ?: false) } && objects(other).all { it is String }
        }
    }

    private fun characters(answer: JSONObject?) = objects(answer?.optJSONArray("characters")).filterIsInstance<JSONObject>()
    private fun strings(a: JSONArray?) = objects(a).filterIsInstance<String>()
    private fun JSONObject.ident() = opt("id")?.toString().orEmpty()

    /** A character's candidates of this request, without repeats; names first (the most complete first), then
     * titles and families: a name anchors a merge, never a generic title. */
    fun refs(raw: JSONObject, r: BookPreparationPlan.Request): List<String> {
        val byId = r.byId
        return strings(raw.optJSONArray("candidates")).filter { it in byId }.distinct().sortedWith(
            compareBy<String> { byId.getValue(it).kind != "name" }.thenBy { -byId.getValue(it).key.split(' ').size }
                .thenBy { -byId.getValue(it).count }.thenBy { it })
    }

    // ------------------------------------------------------------ votes

    /** Several independent answers → one: together only those the majority put together. Literary matching changes
     * from run to run; voting removes random merges and random losses. */
    fun combine(answers: List<JSONObject>, r: BookPreparationPlan.Request): JSONObject {
        if (answers.size == 1) return answers[0]
        val need = answers.size / 2 + 1
        val own = r.candidates.map { it.id }
        val ownSet = own.toSet()
        val together = HashMap<Pair<String, String>, Int>()
        val person = HashMap<String, Int>()
        data class Group(val rank: Int, val raw: JSONObject, val members: List<String>)
        val runs = answers.map { answer ->
            characters(answer).mapIndexed { rank, raw ->
                val members = strings(raw.optJSONArray("candidates")).filter { it in ownSet }.distinct()
                for (a in members) person[a] = (person[a] ?: 0) + 1
                for (a in members) for (b in members) if (a < b) together[a to b] = (together[a to b] ?: 0) + 1
                Group(rank, raw, members)
            }
        }
        val parent = HashMap<String, String>().apply { own.forEach { put(it, it) } }
        fun root(x: String): String { var y = x; while (parent[y] != y) { parent[y] = parent.getValue(parent.getValue(y)); y = parent.getValue(y) }; return y }
        for ((pair, n) in together) if (n >= need && (person[pair.first] ?: 0) >= need && (person[pair.second] ?: 0) >= need)
            parent[root(pair.first)] = root(pair.second)
        val components = linkedMapOf<String, MutableList<String>>()
        for (x in own) if ((person[x] ?: 0) >= need) components.getOrPut(root(x)) { mutableListOf() } += x
        data class Entry(val rank: Double, val ids: LinkedHashMap<String, Int>, val name: String, val gender: String, val refs: List<String>)
        val entries = components.values.map { refs ->
            val ids = linkedMapOf<String, Int>(); val names = linkedMapOf<String, Int>(); val genders = linkedMapOf<String, Int>()
            val ranks = mutableListOf<Int>()
            for (groups in runs) {
                val hits = groups.filter { g -> g.members.any { it in refs } }
                ranks += hits.minOfOrNull { it.rank } ?: groups.size
                for (g in hits) {
                    val weight = g.members.count { it in refs }
                    ids.merge(g.raw.ident(), weight, Int::plus)
                    names.merge(g.raw.optString("name"), weight, Int::plus)
                    genders.merge(g.raw.optString("gender").takeIf { it in listOf("m", "f", "?") } ?: "?", weight, Int::plus)
                }
            }
            Entry(ranks.average(), ids, names.maxByOrNull { it.value }?.key.orEmpty(), genders.maxByOrNull { it.value }?.key ?: "?", refs)
        }
        // The id given to this group most often; a disputed id goes to the group with more votes.
        val taken = HashMap<Int, String>()
        val offers = entries.flatMapIndexed { n, e -> e.ids.map { (ident, v) -> Triple(v, n, ident) } }
            .sortedWith(compareByDescending<Triple<Int, Int, String>> { it.first }.thenByDescending { it.second }.thenByDescending { it.third })
        for ((_, n, ident) in offers) if (ident !in taken.values && n !in taken) taken[n] = ident
        val result = JSONArray()
        for ((n, e) in entries.withIndex().sortedBy { it.value.rank }) result.put(JSONObject().put("id", taken[n] ?: "person_${n + 1}")
            .put("name", e.name).put("gender", e.gender).put("candidates", JSONArray(e.refs)))
        val placed = entries.flatMap { it.refs }.toSet()
        return JSONObject().put("characters", result).put("other", JSONArray(own.filter { it !in placed })).put("votes", answers.size)
    }

    // ------------------------------------------------------------ merge verification

    fun verifyPrompt(r: BookPreparationPlan.Request, answer: JSONObject, collection: Boolean): String? {
        val groups = characters(answer).mapNotNull { raw ->
            val refs = refs(raw, r)
            if (refs.size < 2) null else (listOf("Группа id=${raw.ident()}. Главный: ${CastPrompts.describe(r.byId.getValue(refs[0]))}") +
                refs.drop(1).map { "  проверить ${CastPrompts.describe(r.byId.getValue(it))}" }).joinToString("\n")
        }
        if (groups.isEmpty()) return null
        return CastPrompts.verify(if (collection) "рассказе «${r.title}»" else "книге «${r.title}»", r.prefix, groups)
    }

    /** Every merge of the main answer is checked exactly once. */
    fun verificationComplete(checked: JSONObject?, answer: JSONObject, r: BookPreparationPlan.Request): Boolean {
        if (checked == null || !valid(checked, Kind.VERIFY)) return false
        val expected = characters(answer).flatMap { raw -> refs(raw, r).let { refs -> refs.drop(1).map { Triple(raw.ident(), refs[0], it) } } }
        val actual = objects(checked.optJSONArray("checks")).filterIsInstance<JSONObject>()
            .map { Triple(it.optString("character"), it.optString("anchor"), it.optString("candidate")) }
        return actual.groupingBy { it }.eachCount() == expected.groupingBy { it }.eachCount() && actual.size == actual.toSet().size
    }

    /** (character, anchor, candidate) → verdict; a repeated check is "invalid". Unbound verdicts are not used. */
    fun verdicts(checked: JSONObject?, answer: JSONObject?, r: BookPreparationPlan.Request): Map<Triple<String, String, String>, String> {
        val expected = HashMap<String, MutableList<Pair<String, String>>>()
        for (raw in characters(answer)) {
            val refs = refs(raw, r)
            for (ref in refs.drop(1)) expected.getOrPut(ref) { mutableListOf() } += raw.ident() to refs[0]
        }
        val out = linkedMapOf<Triple<String, String, String>, String>()
        val seen = HashMap<Triple<String, String, String>, Int>()
        for (c in objects(checked?.optJSONArray("checks")).filterIsInstance<JSONObject>()) {
            val ref = c.opt("candidate") as? String ?: continue
            val character = c.opt("character") as? String ?: continue
            val anchor = c.opt("anchor") as? String ?: continue
            if ((character to anchor) !in expected[ref].orEmpty()) continue
            val key = Triple(character, anchor, ref)
            seen[key] = (seen[key] ?: 0) + 1
            out[key] = if (seen[key] == 1) c.optString("verdict") else "invalid"
        }
        return out
    }

    private val PATRONYMIC_SHORT = listOf(Regex("ович$") to "ыч", Regex("евич$") to "ич")
    private fun nameWords(key: String) = key.split(' ').filter { it.isNotEmpty() }.map { w ->
        PATRONYMIC_SHORT.fold(w) { acc, (pattern, short) -> pattern.replace(acc, short) } }.toSet()

    /** A merge needs "same"; "unsure" only when the short name is wholly part of the full one («Аглая» ⊂ «Аглая
     * Ивановна», «Евгений Павлыч» ⊂ «Евгений Павлович Радомский») and of nobody else's. */
    private fun mergeAccepted(verdict: String?, anchor: String, ref: String, group: List<String>, r: BookPreparationPlan.Request): Boolean {
        if (verdict == "same") return true
        if (verdict != "unsure") return false
        val a = r.byId.getValue(anchor); val b = r.byId.getValue(ref)
        if (a.kind != "name" || b.kind != "name") return false
        val wa = nameWords(a.key); val wb = nameWords(b.key)
        val (short, long) = if (wa.size <= wb.size) wa to wb else wb to wa
        if (short.isEmpty() || !long.containsAll(short) || short.size == long.size) return false
        if (a.gender in listOf("m", "f") && b.gender in listOf("m", "f") && a.gender != b.gender) return false
        return r.candidates.none { it.id !in group && it.kind == "name" && nameWords(it.key).containsAll(short) }
    }

    // ------------------------------------------------------------ «who is this» passages

    /** (character id, candidate) for every title or bare surname the answer gave a character, plus titles left in
     * «прочие» (in single chapters they may still be unambiguous). */
    fun labelTargets(r: BookPreparationPlan.Request, answer: JSONObject): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (raw in characters(answer)) {
            val refs = refs(raw, r)
            for (ref in refs) {
                val c = r.byId.getValue(ref)
                if (c.contexts.size < MIN_LABEL_ANSWERS) continue
                // The only name of the character and nobody else carries the surname: nobody to confuse it with.
                if (c.kind != "title" && refs.size == 1 && r.candidates.none { x -> x.id != ref && x.kind == "name" &&
                        x.key.split(' ').let { it.size > 1 && it.last() == c.key } }) continue
                out += raw.ident() to ref
            }
        }
        for (ref in strings(answer.optJSONArray("other")).distinct()) {
            val c = r.byId[ref] ?: continue
            if (c.kind == "title" && c.contexts.size >= MIN_LABEL_ANSWERS && c.speaker >= 2) out += "" to ref
        }
        return out
    }

    class LabelRequest(val suffix: String, val prompt: String, val lo: Int, val hi: Int)

    /** Passages in portions of [LABEL_BATCH]; a title of a novel — a separate request per chapter, so a passage of
     * one chapter never suggests the answer for another. */
    fun labelRequests(r: BookPreparationPlan.Request, answer: JSONObject, ref: String, collection: Boolean): List<LabelRequest> {
        val c = r.byId.getValue(ref)
        val total = c.contexts.size
        val where = c.contextSections
        val parts = mutableListOf<Pair<Int, Int>>()
        if (c.kind == "title" && where.toSet().size > 1 && where.size == total) {
            var lo = 0
            for (i in 1..total) if (i == total || where[i] != where[lo] || i - lo >= LABEL_BATCH) { parts += lo to i; lo = i }
        } else for (lo in 0 until total step LABEL_BATCH) parts += lo to minOf(total, lo + LABEL_BATCH)
        return parts.mapIndexed { n, (lo, hi) ->
            LabelRequest(if (parts.size == 1) ".label.$ref" else ".label.$ref.${n + 1}", labelPrompt(r, answer, ref, collection, lo, hi), lo, hi)
        }
    }

    private fun labelPrompt(r: BookPreparationPlan.Request, answer: JSONObject, ref: String, collection: Boolean, lo: Int, hi: Int): String {
        val people = characters(answer).map { raw ->
            val forms = linkedMapOf<String, Int>()
            for (x in refs(raw, r)) if (x != ref) for ((f, n) in r.byId.getValue(x).forms) forms.merge(f, n, Int::plus)
            val names = forms.entries.sortedByDescending { it.value }.take(6).joinToString(", ") { it.key }
            "- ${raw.ident()}: ${raw.optString("name")}" + if (names.isNotEmpty()) " ($names)" else ""
        }
        val c = r.byId.getValue(ref)
        return CastPrompts.labels(if (collection) "рассказа «${r.title}»" else "книги «${r.title}»", people, c.display, c.contexts.subList(lo, hi))
    }

    /** Votes «who is this» for one portion; null — incomplete answer or repeated numbers. */
    fun labelVotes(checked: JSONObject?, count: Int): List<String>? {
        if (checked == null || !valid(checked, Kind.LABELS)) return null
        val answers = objects(checked.optJSONArray("answers")).filterIsInstance<JSONObject>()
        if (answers.map { it.getInt("n") }.sorted() != (1..count).toList()) return null
        return answers.sortedBy { it.getInt("n") }.map { it.getString("who") }
    }

    /** (keep, for [owner], clear answers). */
    fun labelDecision(votes: Map<String, Int>, owner: String?, minimum: Int = MIN_LABEL_ANSWERS): Triple<Boolean, Int, Int> {
        val known = votes.filterKeys { it != "unsure" }.values.sum()
        val own = votes[owner] ?: 0
        val enough = known >= maxOf(minimum.toDouble(), votes.values.sum() / 2.0)
        return Triple(enough && own >= DOMINANCE * known, own, known)
    }

    /** Whose title in each chapter. A general may «travel»: Epanchin in some chapters, Ivolgin in others. A chapter
     * with a sure majority goes to that person; with disagreement — «прочие»; with one or two mentions — like the
     * neighbouring chapters before and after if they agree, otherwise like the whole book. */
    fun titlePlan(whos: List<String>, where: List<String>, sections: List<String>, people: Set<String>, hint: String = "",
                  soleTitlePeople: Set<String> = emptySet()): Map<String, String?> {
        fun top(votes: Map<String, Int>): String? = votes.filterKeys { it in people }.entries
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })?.key
        val every = whos.groupingBy { it }.eachCount()
        val best = top(every)
        val agrees = hint.isEmpty() || hint == best || hint in soleTitlePeople
        val whole = if (best != null && labelDecision(every, best).first && agrees) best else null
        val state = linkedMapOf<String, Pair<String, String?>>()
        for (sid in sections) {
            val votes = whos.zip(where).filter { it.second == sid }.groupingBy { it.first }.eachCount()
            if (votes.isEmpty()) { state[sid] = "none" to null; continue }
            val lead = top(votes)
            var (keep, own, known) = if (lead != null) labelDecision(votes, lead, MIN_CHAPTER_ANSWERS) else Triple(false, 0, 0)
            if (keep && hint.isNotEmpty() && lead != hint && hint !in soleTitlePeople) { keep = false; own = 0 }
            val rivals = votes.filterKeys { it in people && it != lead }.values.sum()
            if (!keep && lead != null && lead == hint && rivals == 0 && known >= MIN_CHAPTER_ANSWERS && own >= AGREED_DOMINANCE * known) keep = true
            state[sid] = if (keep) "sure" to lead else if (known < MIN_CHAPTER_ANSWERS) "few" to null else "split" to null
        }
        val sure = sections.withIndex().filter { state.getValue(it.value).first == "sure" }.map { it.index to state.getValue(it.value).second }
        return sections.withIndex().associate { (i, sid) ->
            val (kind, owner) = state.getValue(sid)
            sid to when (kind) {
                "sure" -> owner
                "split" -> null
                "few" -> {
                    val before = sure.lastOrNull { it.first < i }?.second
                    val after = sure.firstOrNull { it.first > i }?.second
                    if (before != null && before == after) before else whole
                }
                else -> whole
            }
        }
    }

    // ------------------------------------------------------------ the cast

    data class Character(val id: String, var name: String, val gender: String, val priority: Int, val refs: MutableList<String>,
                         val sourceId: String) {
        var mentions = 0
        var speaker = 0
        var forms: List<String> = emptyList()
    }
    data class Cast(val sections: List<String>, val characters: List<Character>, val other: List<String>,
                    val ambiguous: Set<String>, val dropped: List<String>, val problems: List<String>)

    /** The checked answer: every candidate belongs to exactly one character or to «прочие». A character keeps only
     * aliases confirmed by the verification («same») that do not contradict it by gender; the rest — «прочие». */
    fun build(r: BookPreparationPlan.Request, answer: JSONObject?, verdicts: Map<Triple<String, String, String>, String>,
              prefix: String, labels: Map<Pair<String, String>, Map<String, Int>> = emptyMap()): Cast {
        val byId = r.byId
        val problems = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val ownIds = r.candidates.map { it.id }.toSet()
        val used = linkedMapOf<String, String>()
        val characters = mutableListOf<Character>()
        if (answer == null) problems += "нет ответа LLM — все кандидаты в «прочих»"
        val raws = objects(answer?.optJSONArray("characters"))
        val ownership = HashMap<String, Int>()
        for (raw in raws.filterIsInstance<JSONObject>()) for (ref in strings(raw.optJSONArray("candidates"))) if (ref in ownIds) ownership.merge(ref, 1, Int::plus)
        val explicitOther = strings(answer?.optJSONArray("other"))
        for (ref in explicitOther) if (ref in ownIds) ownership.merge(ref, 1, Int::plus)
        val conflicts = ownership.filterValues { it > 1 }.keys
        if (conflicts.isNotEmpty()) problems += "повторные кандидаты → прочие: " + conflicts.sorted().joinToString(", ")
        for ((priority, value) in raws.withIndex()) {
            val raw = value as? JSONObject ?: run { problems += "персонаж должен быть объектом — пропущен"; null } ?: continue
            val bare = raw.ident().lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
            val cid = if (bare.isEmpty()) "" else prefix + bare
            if (cid.isEmpty() || cid.endsWith("author") || cid.endsWith("other") || cid.length > 80 || characters.any { it.id == cid }) {
                problems += "идентификатор «${raw.ident()}» пустой, служебный или повторяется — персонаж пропущен"; continue
            }
            val gender = raw.optString("gender").takeIf { it in listOf("m", "f", "?") } ?: "?"
            val refs = refs(raw, r)
            if (refs.isEmpty()) { problems += "$cid: нет допустимых кандидатов — пропущен"; continue }
            val anchor = refs[0]
            val own = mutableListOf<String>()
            for (ref in refs) {
                val c = byId.getValue(ref)
                when {
                    ref in conflicts -> dropped += "$ref ${c.display} → прочие: несколько владельцев"
                    c.kind == "family" -> dropped += "$ref ${c.display} → прочие: семья, не один человек"
                    gender in listOf("m", "f") && c.gender in listOf("m", "f") && c.gender != gender &&
                        c.genderSource().second in listOf("verb", "Patr", "Title") -> dropped += "$ref ${c.display} → прочие: род ${c.gender}, у $cid $gender"
                    ref != anchor && (anchor !in own || verdicts.isEmpty() ||
                        !mergeAccepted(verdicts[Triple(raw.ident(), anchor, ref)], anchor, ref, refs, r)) ->
                        dropped += "$ref ${c.display} → прочие: склейка с $anchor не подтверждена для $cid"
                    else -> { used[ref] = cid; own += ref }
                }
            }
            if (own.isEmpty()) { problems += "$cid: нет ни одного кандидата — пропущен"; continue }
            characters += Character(cid, raw.optString("name"), gender, priority, own, raw.ident())
        }

        fun dropWithDependents(ch: Character, ref: String, reason: String) {
            val wasAnchor = ch.refs[0] == ref
            ch.refs.remove(ref); used[ref] = "other"
            dropped += "$ref ${byId.getValue(ref).display} → прочие: $reason"
            if (wasAnchor && byId.getValue(ref).kind == "name") {
                for (dependent in ch.refs) {
                    used[dependent] = "other"
                    dropped += "$dependent ${byId.getValue(dependent).display} → прочие: склейка зависит от неоднозначной формы $ref"
                }
                ch.refs.clear()
            }
        }
        // Checked by passages of the whole book: a title or bare surname stays only if it is almost always this person.
        for (ch in characters) for (ref in ch.refs.toList()) {
            val votes = labels[ch.sourceId to ref] ?: continue
            if (ref !in ch.refs) continue
            val (keep, own, known) = labelDecision(votes, ch.sourceId)
            if (keep) continue
            val rivals = votes.entries.sortedByDescending { it.value }.filter { it.key != ch.sourceId }.joinToString(", ") { "${it.key} ${it.value}" }
            dropWithDependents(ch, ref, "в отрывках это ${ch.id} $own из $known понятных" + if (rivals.isNotEmpty()) " (ещё: $rivals)" else "")
        }
        // A bare surname shared by independently named relatives is ambiguous too (their full names stay).
        for (ch in characters) for (ref in ch.refs.toList()) {
            val c = byId.getValue(ref)
            if ((ch.sourceId to ref) in labels || ref !in ch.refs) continue
            if (c.kind != "name" || c.key.split(' ').size != 1 || (c.roles["Surn"] ?: 0) == 0) continue
            val relatives = r.candidates.filter { x -> x.kind == "name" && x.key.split(' ').let { it.size > 1 && it.last() == c.key } &&
                (used[x.id] ?: "other") != ch.id }
            if (relatives.isEmpty()) continue
            // «Рогожин» 26 times, the father «Семен Парфенович Рогожин» 2: the surname stays with Parfyon.
            val rival = relatives.sumOf { it.count }
            if (c.count >= DOMINANCE * (c.count + rival)) continue
            dropWithDependents(ch, ref, "фамилия встречается в других полных именах ($rival упоминаний против ${c.count})")
        }
        // Any named candidate can contradict a title, even if the LLM omitted that person.
        for (ch in characters) for (ref in ch.refs.filter { byId.getValue(it).kind == "title" }) {
            if ((ch.sourceId to ref) in labels) continue
            val word = byId.getValue(ref).key
            val per = characters.associate { other -> other.id to other.refs.filter { it != ref }.sumOf { byId.getValue(it).titles[word] ?: 0 } }
            val unassigned = r.candidates.filter { it.kind == "name" && (used[it.id] ?: "other") == "other" }.sumOf { it.titles[word] ?: 0 }
            val total = per.values.sum() + unassigned
            if ((total > 0 && per.getValue(ch.id) < total) || (total == 0 && ch.refs.size == 1)) {
                ch.refs.remove(ref); used[ref] = "other"
                val otherOwner = if (total > 0) per.maxByOrNull { it.value }?.key else null
                dropped += "$ref ${byId.getValue(ref).display} → прочие: обращение связано с именем ${ch.id} ${per.getValue(ch.id)} из $total раз" +
                    if (otherOwner != null && otherOwner != ch.id) " (чаще $otherOwner)" else ""
            }
        }
        val kept = characters.filter { it.refs.isNotEmpty() }
        for (ch in kept) {
            // A name only from the book's words; otherwise the most complete form found.
            val words = ch.refs.flatMap { byId.getValue(it).key.split(' ') }.toSet()
            val nameWords = Regex("[А-ЯЁа-яё-]+").findAll(ch.name).map { NameCandidates.key(it.value) }.toList()
            if (nameWords.isEmpty() || nameWords.any { it !in words } || ch.name.any { it in "()" }) {
                val longest = ch.refs.map { byId.getValue(it) }.filter { it.kind == "name" }
                    .maxWithOrNull(compareBy<NameCandidates.Candidate> { it.key.split(' ').size }.thenBy { it.count }) ?: byId.getValue(ch.refs[0])
                ch.name = longest.key.split(' ').joinToString(" ") { if (longest.kind == "name") it.replaceFirstChar(Char::uppercaseChar) else it }
            }
        }
        val other = mutableListOf<String>()
        for (ref in explicitOther) if (ref in ownIds && ref !in used) { used[ref] = "other"; other += ref }
        for (line in dropped) {
            val ref = line.substringBefore(' ')
            if ((used[ref] ?: "other") == "other" && ref !in other) { used[ref] = "other"; other += ref }
        }
        val missing = r.candidates.map { it.id }.filter { it !in used }
        if (missing.isNotEmpty()) { problems += "не распределено ${missing.size} — отнесены к «прочим»"; other += missing }
        val alias = linkedMapOf<String, MutableSet<String>>()
        val scope = r.sections.toSet()
        fun within(counts: Map<String, Int>) = counts.filterKeys { it in scope }.values.sum()
        for (ch in kept) {
            val forms = linkedMapOf<String, Int>()
            ch.mentions = ch.refs.sumOf { within(byId.getValue(it).sectionCounts) }
            ch.speaker = ch.refs.sumOf { within(byId.getValue(it).speakerCounts) }
            for (ref in ch.refs) {
                val c = byId.getValue(ref)
                for ((f, n) in c.forms) forms.merge(f, n, Int::plus)
                alias.getOrPut(c.key) { mutableSetOf() } += ch.id
                for (f in c.forms.keys) alias.getOrPut(NameCandidates.key(f)) { mutableSetOf() } += ch.id
            }
            ch.forms = forms.entries.sortedByDescending { it.value }.map { it.key }
        }
        for (ref in other) {
            val c = byId.getValue(ref)
            alias.getOrPut(c.key) { mutableSetOf() } += "other"
            for (f in c.forms.keys) alias.getOrPut(NameCandidates.key(f)) { mutableSetOf() } += "other"
        }
        return Cast(r.sections, kept.sortedWith(compareBy<Character> { it.priority }.thenBy { it.id }), other,
            alias.filterValues { it.size > 1 }.keys, dropped, problems)
    }

    /** All casts of one request: chapters with the same decisions on titles share one set of characters.
     * [whos]: «who is this» per passage for each checked candidate (absent — not checked). */
    fun casts(r: BookPreparationPlan.Request, answer: JSONObject, verdicts: Map<Triple<String, String, String>, String>,
              whos: Map<Pair<String, String>, List<String>>, collection: Boolean): List<Cast> {
        val labels = linkedMapOf<Pair<String, String>, Map<String, Int>>()
        val plans = sortedMapOf<String, Map<String, String?>>()
        val people = characters(answer).map { it.ident() }.toSet()
        for ((target, answers) in whos) {
            val (rawId, ref) = target
            val c = r.byId.getValue(ref)
            if (c.kind == "title") {
                val where = c.contextSections.takeIf { it.size == answers.size } ?: List(answers.size) { r.sections[0] }
                val sole = characters(answer).filter { ch -> strings(ch.optJSONArray("candidates")).filter { it in r.byId } == listOf(ref) }
                    .map { it.ident() }.toSet()
                plans[ref] = titlePlan(answers, where, r.sections, people, rawId, sole)
            } else if (rawId.isNotEmpty()) labels[rawId to ref] = answers.groupingBy { it }.eachCount()
        }
        val groups = linkedMapOf<List<Pair<String, String?>>, MutableList<String>>()
        for (sid in r.sections) groups.getOrPut(plans.map { (ref, plan) -> ref to plan[sid] }) { mutableListOf() } += sid
        val prefix = if (collection) "${r.name}." else ""
        return groups.map { (signature, sids) ->
            val variant = JSONObject(answer.toString())
            val variantVerdicts = verdicts.toMutableMap()
            val variantLabels = labels.toMutableMap()
            for ((ref, owner) in signature) {
                for (ch in characters(variant)) ch.optJSONArray("candidates")?.let { a -> ch.put("candidates", JSONArray(objects(a).filter { it != ref })) }
                variant.put("other", JSONArray(strings(variant.optJSONArray("other")).filter { it != ref }))
                val raw = characters(variant).firstOrNull { it.ident() == owner }
                if (raw == null) { variant.getJSONArray("other").put(ref); continue }
                raw.getJSONArray("candidates").put(ref)
                val anchor = refs(raw, r)[0]
                if (anchor != ref) variantVerdicts[Triple(owner!!, anchor, ref)] = "same" // confirmed by this chapter's passages
                variantLabels[owner!! to ref] = mapOf(owner to MIN_LABEL_ANSWERS)
            }
            build(r.copy(sections = sids), variant, variantVerdicts, prefix, variantLabels)
        }
    }

    /** One cast of the .mytts-book file: no book text, only names and their forms. */
    fun export(cast: Cast, r: BookPreparationPlan.Request, maxForms: Int = 24): JSONObject {
        val characters = JSONArray(cast.characters.map { ch ->
            JSONObject().put("id", ch.id).put("name", ch.name).put("gender", ch.gender).put("speaker", ch.speaker)
                .put("mentions", ch.mentions).put("forms", JSONArray(ch.forms.take(maxForms).filter { NameCandidates.key(it) !in cast.ambiguous }))
        })
        val other = linkedSetOf<String>()
        for (ref in cast.other) {
            val c = r.byId.getValue(ref)
            other += c.display; other += c.key
            other += c.forms.entries.sortedByDescending { it.value }.take(maxForms).map { it.key }
        }
        other += cast.ambiguous.sorted()
        return JSONObject().put("sections", JSONArray(cast.sections)).put("characters", characters).put("other", JSONArray(other.toList()))
    }
}
