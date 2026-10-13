package com.brahmadeo.supertonic.tts.books

import org.json.JSONObject

/** The voices a book has been read with, fixed: once a character, the narrator or the «прочие» got a voice, they keep
 * it — a regenerated `.mytts-book` (another order of the cast, other character ids: «ganya» became
 * «gavrila_ardalionovich_ivolgin») must not move voices between characters. A character is found by its id, otherwise
 * by the name forms it shares with a fixed character ([resolve]). Only «Расставить голоса» starts over (pure, tested). */
data class BookVoiceBinding(val characters: Map<String, Fixed> = emptyMap(), val roles: Map<String, String> = emptyMap()) {
    data class Fixed(val voice: String, val forms: Set<String>)

    /** Fixed voices of [cast] (character id -> voice). The same id keeps its voice; the other fixed characters go
     * one to one to the character sharing most name forms with them (ties are left alone), so «general_ivolgin» and
     * «ganya» both called «Иволгин» do not end up with one voice. */
    fun resolve(cast: List<BookPackage.Character>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (ch in cast) characters[ch.id]?.let { out[ch.id] = it.voice }
        val free = characters.keys.filter { id -> cast.none { it.id == id } }.toMutableSet()
        val pending = cast.filter { it.id !in out }
        val scores = pending.flatMap { ch -> val own = forms(ch); free.map { id -> Triple(ch.id, id, characters.getValue(id).forms.count { it in own }) } }
            .filter { it.third > 0 }.sortedByDescending { it.third }
        val done = HashSet<String>()
        for ((ch, id, score) in scores) {
            if (ch in done || id !in free) continue
            // Another pairing of the same strength: ambiguous, no voice from the old file.
            if (scores.any { it.third == score && it != Triple(ch, id, score) && (it.first == ch || it.second == id) && it.first !in done && it.second in free }) continue
            out[ch] = characters.getValue(id).voice; done += ch; free -= id
        }
        return out
    }

    /** [assigned] (character id -> voice) of [cast] fixed for those not fixed yet (also refreshed under their new
     * ids after a regeneration); null when nothing changed. */
    fun with(cast: List<BookPackage.Character>, assigned: Map<String, String>, usable: (String) -> Boolean = { true }): BookVoiceBinding? {
        // A voice of a model no longer chosen for the book is replaced by the new one.
        val added = cast.filter { characters[it.id]?.voice?.let(usable) != true }
            .mapNotNull { ch -> assigned[ch.id]?.takeIf { it != characters[ch.id]?.voice }?.let { ch.id to Fixed(it, forms(ch)) } }
        return if (added.isEmpty()) null else copy(characters = characters + added)
    }

    fun withRole(role: String, voice: String): BookVoiceBinding? = if (roles[role] == voice) null else copy(roles = roles + (role to voice))

    fun json(): String = JSONObject().put("characters", JSONObject().apply {
        for ((id, f) in characters) put(id, JSONObject().put("voice", f.voice).put("forms", org.json.JSONArray(f.forms.sorted())))
    }).put("roles", JSONObject(roles)).toString()

    companion object {
        /** Name forms that tell a character apart: lowercase, ё as е, at least four letters (not «князь» alone: titles
         * are shared, the single-match rule above drops them anyway). */
        fun forms(ch: BookPackage.Character): Set<String> = (ch.forms + ch.name)
            .map { it.lowercase().replace('ё', 'е').trim() }.filter { it.count(Char::isLetter) >= 4 }.toSet()

        fun parse(json: String?): BookVoiceBinding = runCatching {
            val o = JSONObject(json ?: return BookVoiceBinding())
            val chars = o.optJSONObject("characters")
            val roles = o.optJSONObject("roles")
            BookVoiceBinding(
                chars?.keys()?.asSequence()?.associateWith { id ->
                    val f = chars.getJSONObject(id)
                    val list = f.optJSONArray("forms")
                    Fixed(f.getString("voice"), (0 until (list?.length() ?: 0)).map { list!!.getString(it) }.toSet())
                }.orEmpty(),
                roles?.keys()?.asSequence()?.associateWith { roles.getString(it) }.orEmpty())
        }.getOrDefault(BookVoiceBinding())
    }
}
