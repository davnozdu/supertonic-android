package com.brahmadeo.supertonic.tts.books.prepare

import org.json.JSONArray
import org.json.JSONObject

/** Only verified, unambiguous aliases reach a character. Everything else remains in «прочие». */
object CastCheck {
    data class Character(val id: String, var name: String, var gender: String, val refs: MutableList<String>)
    data class Result(val characters: List<Character>, val other: List<String>, val dropped: List<String>, val problems: List<String>) {
        fun export(candidates: List<NameCandidates.Candidate>, sections: List<String>): JSONObject {
            val byId = candidates.associateBy { it.id }
            return JSONObject().put("sections", JSONArray(sections)).put("characters", JSONArray(characters.map { ch ->
                val own = ch.refs.map { byId.getValue(it) }
                val forms = linkedMapOf<String, Int>()
                for (c in own) for ((form, n) in c.forms) forms[form] = (forms[form] ?: 0) + n
                JSONObject().put("id", ch.id).put("name", ch.name).put("gender", ch.gender)
                    .put("speaker", own.sumOf { it.speaker }).put("mentions", own.sumOf { it.count })
                    .put("forms", JSONArray(forms.entries.sortedByDescending { it.value }.take(24).map { it.key }))
            })).put("other", JSONArray(other.map { byId.getValue(it).display }))
        }
    }

    fun parse(raw: String, verify: Boolean = false): JSONObject {
        val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "LLM не вернула JSON; повторите подготовку" }
        val o = try { JSONObject(raw.substring(start, end + 1)) } catch (_: Exception) {
            error("LLM вернула повреждённый JSON; повторите подготовку")
        }
        require(o.optJSONArray(if (verify) "checks" else "characters") != null && (verify || o.optJSONArray("other") != null)) {
            "В ответе LLM нет нужных списков; повторите подготовку"
        }
        return o
    }

    fun apply(candidates: List<NameCandidates.Candidate>, answer: JSONObject, verification: JSONObject?, prefix: String = ""): Result {
        val byId = candidates.associateBy { it.id }
        val used = linkedMapOf<String, String>()
        val characters = mutableListOf<Character>()
        val dropped = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val checks = verification?.optJSONArray("checks")
        val verdicts = linkedMapOf<String, MutableList<String>>()
        if (checks != null) for (i in 0 until checks.length()) checks.optJSONObject(i)?.let {
            verdicts.getOrPut(it.optString("candidate")) { mutableListOf() }.add(it.optString("verdict"))
        }
        val rawCharacters = answer.optJSONArray("characters") ?: JSONArray()
        for (i in 0 until rawCharacters.length()) {
            val raw = rawCharacters.optJSONObject(i) ?: continue
            val bare = raw.optString("id").lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
            val cid = prefix + bare
            if (bare.isEmpty() || bare.endsWith("author") || bare.endsWith("other") || cid.length > 80 || characters.any { it.id == cid }) {
                problems += "Пустой, служебный или повторный идентификатор персонажа"; continue
            }
            var gender = raw.optString("gender").takeIf { it in listOf("m", "f", "?") } ?: "?"
            val own = mutableListOf<String>()
            val refs = raw.optJSONArray("candidates") ?: JSONArray()
            for (j in 0 until refs.length()) {
                val ref = refs.optString(j)
                val c = byId[ref]
                when {
                    c == null -> problems += "$cid: несуществующий кандидат $ref"
                    ref in used -> problems += "$ref указан дважды — оставлен у ${used[ref]}"
                    own.isNotEmpty() && verdicts[ref]?.singleOrNull() != "same" -> dropped += "$ref ${c.display} → прочие: склейка не подтверждена"
                    own.isNotEmpty() && gender in listOf("m", "f") && c.gender in listOf("m", "f") && c.gender != gender &&
                        c.genderSource().second in listOf("verb", "Patr", "Title") -> dropped += "$ref ${c.display} → прочие: конфликт рода"
                    else -> {
                        if (own.isEmpty() && c.genderSource().second in listOf("verb", "Patr", "Title")) gender = c.gender
                        used[ref] = cid; own += ref
                    }
                }
            }
            if (own.isEmpty()) { problems += "$cid: нет кандидатов"; continue }
            characters += Character(cid, raw.optString("name"), gender, own)
        }
        // A bare title is an alias only when >=80% of its named uses belong to this person.
        for (ch in characters) for (ref in ch.refs.drop(1).filter { byId.getValue(it).kind == "title" }) {
            val word = byId.getValue(ref).key
            val per = characters.associate { c -> c.id to c.refs.filter { it != ref }.sumOf { byId.getValue(it).titles[word] ?: 0 } }
            val total = per.values.sum()
            if (total == 0 || per.getValue(ch.id) < 0.8 * total) {
                ch.refs.remove(ref); used[ref] = "other"
                dropped += "$ref ${byId.getValue(ref).display} → прочие: обращение неоднозначно"
            }
        }
        for (ch in characters) {
            val words = ch.refs.flatMap { byId.getValue(it).key.split(' ') }.toSet()
            val nameWords = NameCandidates.WORD.findAll(ch.name).map { NameCandidates.key(it.value) }.toList()
            if (nameWords.isEmpty() || nameWords.any { it !in words } || ch.name.any { it in "()" }) {
                val c = ch.refs.map { byId.getValue(it) }.filter { it.kind == "name" }
                    .maxWithOrNull(compareBy<NameCandidates.Candidate> { it.key.split(' ').size }.thenBy { it.count }) ?: byId.getValue(ch.refs[0])
                ch.name = c.key.split(' ').joinToString(" ") { if (c.kind == "name") it.replaceFirstChar(Char::uppercaseChar) else it }
            }
        }
        val other = candidates.filter { used[it.id] == null || used[it.id] == "other" }.map { it.id }
        return Result(characters.sortedWith(compareByDescending<Character> { ch -> ch.refs.sumOf { byId.getValue(it).speaker * 3 + byId.getValue(it).count } }.thenBy { it.id }),
            other, dropped, problems)
    }
}
