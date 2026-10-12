package com.brahmadeo.supertonic.tts.books.prepare

import org.json.JSONObject

/** Instructions copied from tools/characters/book_characters.py; mapping only, no rewriting the book. */
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
- Персонажей в characters расположи по значимости в повествовании: сначала главные, затем
  второстепенные и эпизодические. Порядок нужен для приоритета личного голоса, не для объединения имён.
  Учитывай контекст частых обращений, но не закрепляй неоднозначное обращение за одним человеком.
- Если примеры однозначно описывают отдельного человека, сохрани его персонажем даже при редких
  упоминаниях или участии во вложенном рассказе. Невозможность склеить его с другими именами не
  означает, что надо удалить саму личность: можно оставить одного надёжного кандидата.
- Каждый номер кандидата укажи ровно один раз: либо у одного персонажа, либо в "other".
- Первым в "candidates" ставь самого надёжного кандидата персонажа (самое частое имя).
- name — полное имя из найденных форм (например «Лев Николаевич Мышкин», если такие формы есть);
  ничего не додумывай: ни полных имён, которых нет в тексте, ни пояснений в скобках.
- В "other" — не персонажи (места, книги, исторические лица, которых только упоминают), семьи
  во множественном числе, и всё, что нельзя уверенно отнести к одному лицу.
- Обращение без имени («князь», «генерал») отнеси к персонажу только при отсутствии других
  носителей этого обращения в доступных примерах и связях; если так называют нескольких — в "other".
- Отец и сын, муж и жена с одной фамилией — разные персонажи; общую фамилию без имени, если по
  примерам не ясно, кто это, отправь в "other".
- gender: "m", "f" или "?" — по тексту.
- Не придумывай номера, которых нет в списке. Ответ — только JSON без пояснений:
{"characters": [{"id": "...", "name": "полное имя", "gender": "m", "candidates": ["{p}1", "{p}7"]}], "other": ["{p}9"]}

Кандидаты:
{lines}
"""
    private val VERIFY = """Проверка сопоставления персонажей в {what}.
Для каждого персонажа ниже: главный кандидат и остальные, которых к нему отнесли, с примерами из текста.
Для каждого остального кандидата ответь, тот ли это человек, что и главный:
"same" — несомненно тот же человек; "different" — другой человек или не человек; "unsure" — нельзя
уверенно сказать по примерам. Сомнение — это "unsure". Ответ — только JSON без пояснений:
{"checks": [{"character": "id персонажа", "anchor": "{p}1", "candidate": "{p}5", "verdict": "same"}]}
В каждом checks повтори id персонажа и номер главного кандидата из группы. Проверяй по примерам, не по памяти о книге.

{groups}
"""
    private val LABELS = """Кто назван словом в [[…]] в каждом отрывке из {what}?
Персонажи (id: имя; как ещё называется):
{people}

Для каждого отрывка ответь id персонажа из списка; "other" — другой человек (нет в списке) или не человек;
"unsure" — по отрывку нельзя понять. Решай по самому отрывку: кто говорит, к кому обращаются, кто действует
рядом. Ответ — только JSON без пояснений, по одному ответу на каждый номер:
{"answers": [{"n": 1, "who": "id"}]}

Отрывки (слово «{label}»):
{snippets}
"""
    val schema get() = JSONObject("""{"type": "object", "properties": {"characters": {"type": "array", "items": {"type": "object", "properties": {"id": {"type": "string"}, "name": {"type": "string"}, "gender": {"type": "string", "enum": ["m", "f", "?"]}, "candidates": {"type": "array", "items": {"type": "string"}}}, "required": ["id", "name", "gender", "candidates"]}}, "other": {"type": "array", "items": {"type": "string"}}}, "required": ["characters", "other"]}""")
    val verifySchema get() = JSONObject("""{"type":"object","properties":{"checks":{"type":"array","items":{"type":"object","properties":{"character":{"type":"string"},"anchor":{"type":"string"},"candidate":{"type":"string"},"verdict":{"type":"string","enum":["same","different","unsure"]}},"required":["character","anchor","candidate","verdict"]}}},"required":["checks"]}""")
    val labelSchema get() = JSONObject("""{"type":"object","properties":{"answers":{"type":"array","items":{"type":"object","properties":{"n":{"type":"integer"},"who":{"type":"string"}},"required":["n","who"]}}},"required":["answers"]}""")

    // Single pass, values inserted literally: book text may itself contain «{p}» or «$».
    private val PLACEHOLDER = Regex("\\{(what|p|lines|groups|people|label|snippets)\\}")
    private fun render(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m -> values[m.groupValues[1]] ?: m.value }

    private fun gender(c: NameCandidates.Candidate) = c.gender
    private val ROLE = mapOf("Name" to "имя", "Patr" to "отчество", "Surn" to "фамилия")

    fun main(what: String, prefix: String, candidates: List<NameCandidates.Candidate>): String {
        val ids = candidates.associate { it.key to it.id }
        return render(PROMPT, mapOf("what" to what, "p" to prefix, "lines" to candidates.joinToString("\n") { line(it, ids) }))
    }

    private fun line(c: NameCandidates.Candidate, ids: Map<String, String>): String {
        val bits = mutableListOf("${c.id} | ${c.display}", "упоминаний ${c.count}")
        if (c.speaker > 0) bits += "в ремарках ${c.speaker}"
        bits += "род ${gender(c)}"
        if (c.kind == "family") bits += "мн. число (семья?)"
        if (c.kind == "title") bits += "обращение без имени"
        if (c.roles.isNotEmpty()) bits += c.roles.entries.sortedByDescending { it.value }.joinToString("+") { ROLE.getValue(it.key) }
        if (c.titles.isNotEmpty()) bits += "обращения при имени: " + c.titles.entries.sortedByDescending { it.value }.take(3).joinToString(", ") { it.key }
        val near = c.together.entries.sortedByDescending { it.value }.take(8).mapNotNull { ids[it.key] }.take(4)
        if (near.isNotEmpty()) bits += "рядом: " + near.joinToString(", ")
        // Three examples for frequent candidates and titles (most often confused), one for rare ones.
        val examples = if (c.count >= 30 || c.kind == "title") 3 else if (c.count >= 5) 2 else 1
        return bits.joinToString(" | ") + c.examples.take(examples).joinToString("") { "\n    пример: $it" }
    }

    fun describe(c: NameCandidates.Candidate) = "${c.id} ${c.display} (упоминаний ${c.count}, род ${c.gender}, формы: " +
        c.forms.entries.sortedByDescending { it.value }.take(4).joinToString(", ") { it.key } + ")" +
        c.examples.take(3).joinToString("") { "\n      пример: $it" }

    fun verify(what: String, prefix: String, groups: List<String>) =
        render(VERIFY, mapOf("what" to what, "p" to prefix, "groups" to groups.joinToString("\n\n")))

    fun labels(what: String, people: List<String>, label: String, snippets: List<String>) =
        render(LABELS, mapOf("what" to what, "people" to people.joinToString("\n"), "label" to label,
            "snippets" to snippets.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")))
}
