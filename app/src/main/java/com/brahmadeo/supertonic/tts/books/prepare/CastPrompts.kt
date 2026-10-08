package com.brahmadeo.supertonic.tts.books.prepare

import org.json.JSONObject

/** Instructions copied from mytts-books/mytts_book.py; mapping only, no rewriting the book. */
object CastPrompts {
    private val PROMPT = """Ты помогаешь подготовить {what} к озвучке разными голосами.
Ниже — кандидаты, найденные в тексте автоматически: имена, фамилии, отчества, уменьшительные формы
и обращения («князь», «генеральша»). У каждого кандидата: номер, число упоминаний, сколько раз он
назван в ремарке диалога («— сказал князь»), род по тексту, обращения перед ним, с кем встречается
в одних абзацах, примеры.

Задача: собрать персонажей. Один персонаж часто записан по-разному: фамилия, имя и отчество,
уменьшительное имя, прозвище, обращение, разные падежи. Для каждого персонажа придумай постоянный
идентификатор латиницей (например "myshkin", "nastasya_filippovna") и перечисли ВСЕ номера кандидатов,
которые означают его.

Сопоставление должно быть железным: объединяй кандидатов, только если по тексту несомненно, что это
одно лицо (имя с отчеством стоят вместе, «Лиза» прямо названа «Елизаветой Петровной», примеры говорят
об одном человеке, одна и та же форма в разных падежах). Любое сомнение — кандидат в "other".
Лучше оставить настоящего персонажа в "other", чем склеить двух разных людей.

Правила:
- Каждый номер кандидата укажи ровно один раз: либо у одного персонажа, либо в "other".
- Первым в "candidates" ставь самого надёжного кандидата персонажа (самое частое имя).
- name — полное имя из найденных форм (например «Лев Николаевич Мышкин», если такие формы есть);
  ничего не додумывай: ни полных имён, которых нет в тексте, ни пояснений в скобках.
- В "other" — не персонажи (места, книги, исторические лица, которых только упоминают), семьи
  во множественном числе, и всё, что нельзя уверенно отнести к одному лицу.
- Обращение без имени («князь», «генерал») отнеси к персонажу, которого им называют почти всегда;
  если так называют нескольких — в "other".
- Отец и сын, муж и жена с одной фамилией — разные персонажи; общую фамилию без имени, если по
  примерам не ясно, кто это, отправь в "other".
- gender: "m", "f" или "?" — по тексту.
- Не придумывай номера, которых нет в списке. Ответ — только JSON без пояснений:
{{"characters": [{{"id": "...", "name": "полное имя", "gender": "m", "candidates": ["{p}1", "{p}7"]}}], "other": ["{p}9"]}}

Кандидаты:
{lines}
"""
    private val VERIFY = """Проверка сопоставления персонажей в {what}.
Для каждого персонажа ниже: главный кандидат и остальные, которых к нему отнесли, с примерами из текста.
Для каждого остального кандидата ответь, тот ли это человек, что и главный:
"same" — несомненно тот же человек; "different" — другой человек или не человек; "unsure" — нельзя
уверенно сказать по примерам. Сомнение — это "unsure". Ответ — только JSON без пояснений:
{{"checks": [{{"candidate": "{p}5", "verdict": "same"}}]}}

{groups}
"""
    val schema get() = JSONObject("""{"type": "object", "properties": {"characters": {"type": "array", "items": {"type": "object", "properties": {"id": {"type": "string"}, "name": {"type": "string"}, "gender": {"type": "string", "enum": ["m", "f", "?"]}, "candidates": {"type": "array", "items": {"type": "string"}}}, "required": ["id", "name", "gender", "candidates"]}}, "other": {"type": "array", "items": {"type": "string"}}}, "required": ["characters", "other"]}""")
    val verifySchema get() = JSONObject("""{"type":"object","properties":{"checks":{"type":"array","items":{"type":"object","properties":{"candidate":{"type":"string"},"verdict":{"type":"string","enum":["same","different","unsure"]}},"required":["candidate","verdict"]}}},"required":["checks"]}""")
    private fun render(template: String, values: Map<String, String>): String {
        val out = template.replace("{{", "{").replace("}}", "}")
        return Regex("\\{(what|p|lines|groups)\\}").replace(out) { values[it.groupValues[1]] ?: it.value }
    }
    fun main(what: String, prefix: String, candidates: List<NameCandidates.Candidate>): String {
        val ids = candidates.associate { it.key to it.id }
        return render(PROMPT, mapOf("what" to what, "p" to prefix, "lines" to candidates.joinToString("\n") { line(it, ids) }))
    }
    fun verify(what: String, prefix: String, answer: JSONObject, candidates: List<NameCandidates.Candidate>): String? {
        val byId = candidates.associateBy { it.id }
        val groups = mutableListOf<String>()
        val characters = answer.optJSONArray("characters") ?: return null
        for (i in 0 until characters.length()) {
            val ch = characters.optJSONObject(i) ?: continue
            val refs = ch.optJSONArray("candidates") ?: continue
            val own = (0 until refs.length()).mapNotNull { byId[refs.optString(it)] }.distinctBy { it.id }
            if (own.size < 2) continue
            groups += "Персонаж «${ch.optString("name")}». Главный: ${describe(own[0])}\n" +
                own.drop(1).joinToString("\n") { "  проверить ${describe(it)}" }
        }
        if (groups.isEmpty()) return null
        return render(VERIFY, mapOf("what" to what, "p" to prefix, "groups" to groups.joinToString("\n\n")))
    }
    private fun line(c: NameCandidates.Candidate, ids: Map<String, String>): String {
        val bits = mutableListOf("${c.id} | ${c.display}", "упоминаний ${c.count}")
        if (c.speaker > 0) bits += "в ремарках ${c.speaker}"
        bits += "род ${c.gender}"
        if (c.kind == "family") bits += "мн. число (семья?)"
        if (c.kind == "title") bits += "обращение без имени"
        if (c.roles.isNotEmpty()) bits += c.roles.entries.sortedByDescending { it.value }.joinToString("+") {
            mapOf("Name" to "имя", "Patr" to "отчество", "Surn" to "фамилия").getValue(it.key)
        }
        if (c.titles.isNotEmpty()) bits += "перед ним: " + c.titles.entries.sortedByDescending { it.value }.take(3).joinToString(", ") { it.key }
        val near = c.together.entries.sortedByDescending { it.value }.take(8).mapNotNull { ids[it.key] }.take(4)
        if (near.isNotEmpty()) bits += "рядом: " + near.joinToString(", ")
        return bits.joinToString(" | ") + c.examples.take(if (c.count < 30) 1 else 2).joinToString("") { "\n    пример: $it" }
    }
    private fun describe(c: NameCandidates.Candidate) = "${c.id} ${c.display} (упоминаний ${c.count}, род ${c.gender}, формы: " +
        c.forms.entries.sortedByDescending { it.value }.take(4).joinToString(", ") { it.key } + ")" +
        c.examples.take(2).joinToString("") { "\n      пример: $it" }
}
