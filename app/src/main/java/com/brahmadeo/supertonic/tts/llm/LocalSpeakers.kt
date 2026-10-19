package com.brahmadeo.supertonic.tts.llm

/** Speaker voices without an LLM, for readers that send one sentence at a time (Readest): the LLM wait there would be
 * a pause before every sentence. Russian dialogue is marked well enough to guess from the text itself:
 *  - a line starting with a dash is speech; ", — ответил сосед, —" inside it is the author's remark;
 *  - a sentence right after speech continues it when it talks to someone (я, вы, ты…) or ends with ! or ?;
 *  - «…» at the start of a sentence followed by a dash remark is speech;
 *  - the remark names the speaker: a character of the prepared book by any of its forms, else the gender of the
 *    past-tense verb or pronoun (ответил / ответила, он / она); «— сказал я» in first-person narration is the author;
 *  - a line without a remark is the other participant of the dialogue (turns alternate).
 * Less exact than the LLM, without any delay. One reader at a time: state is not per client. */
class LocalSpeakers {
    data class Person(val id: String, val gender: String, val forms: List<String>)
    private data class Speaker(val role: VoiceRole, val character: String?)

    private val dash = Regex("^[\\s\\p{Z}]*[—–-][\\s\\p{Z}]")
    /** ", — " / "! — " / "? — " / "… — " between speech and remark (and back). */
    private val turn = Regex("(?<=[,.!?…:])[\\s\\p{Z}]+[—–][\\s\\p{Z}]+")
    // Speech in «…», „…“, “…” or "…" before a dash remark: books and readers use all four.
    private val quoteLead = Regex("^[\\s\\p{Z}]*(?:«[^»]+»|„[^“”]+[“”]|“[^”]+”|\"[^\"]+\")[,!?…]?(?=[\\s\\p{Z}]+[—–][\\s\\p{Z}])")
    private val word = Regex("[А-Яа-яЁё]+")
    private val addressing = setOf("я", "мы", "ты", "вы", "вас", "вам", "вами", "меня", "мне", "мной", "тебя", "тебе", "тобой",
        "нас", "нам", "нами", "ваш", "ваша", "ваше", "ваши", "наш", "наша", "наше", "мой", "моя", "моё", "мое", "твой", "твоя")
    private val maleVerb = Regex("^[а-яё]{2,}(л|лся)$")
    private val femaleVerb = Regex("^[а-яё]{2,}(ла|лась)$")

    private var inSpeech = false
    /** Who spoke the line still open (a continuation sentence keeps them). */
    private var current: Speaker? = null
    private val recent = ArrayDeque<Speaker>()

    fun reset() { inSpeech = false; current = null; recent.clear() }

    /** Voice parts of [text] (their texts join back to [text] exactly); [cast]: characters of the recognised book. */
    fun plan(text: String, cast: List<Person> = emptyList(), firstPerson: Boolean = false): List<VoiceRoleText> {
        val author = listOf(VoiceRoleText(text, VoiceRole.AUTHOR))
        if (text.isBlank()) return author
        val dashed = dash.containsMatchIn(text)
        val pieces = speechPieces(text, cast, firstPerson) ?: run {
            // Narration: a following sentence without a dash starts no speech.
            inSpeech = false
            return author
        }
        // Who speaks: the first remark naming someone, else the other participant of the dialogue.
        val remark = pieces.filter { !it.second }.joinToString(" ") { it.first }
        val named = if (remark.isNotBlank()) fromRemark(remark, cast, firstPerson) else null
        // A dash starts a new reply: the other participant. Without it (Readest drops the dash, so a new reply and the
        // next sentence of the same one look alike) the line stays with the current speaker.
        val speaker = named ?: (if (dashed) next() else current ?: next())
        current = speaker
        if (speaker.role != VoiceRole.AUTHOR) remember(speaker)
        inSpeech = pieces.last().second
        val parts = pieces.map { (piece, speech) ->
            if (speech) VoiceRoleText(piece, speaker.role, speaker.character) else VoiceRoleText(piece, VoiceRole.AUTHOR)
        }
        return VoiceRolePlan.safe(text, merge(parts))
    }

    /** (piece, isSpeech) covering the text, or null for plain narration. Speech is recognised by a leading dash, «…»
     * before a remark, a remark inside the sentence («…, — обратился он»), spoken language, or as the continuation of
     * a reply (Readest sends replies without their leading dash). */
    private fun speechPieces(text: String, cast: List<Person>, firstPerson: Boolean): List<Pair<String, Boolean>>? {
        val dashed = dash.containsMatchIn(text)
        val quoted = quoteLead.find(text)
        val remarkInside = !dashed && quoted == null && turn.find(text)?.let { looksLikeRemark(text.substring(it.range.last + 1), cast) } == true
        val spoken = !dashed && quoted == null && !remarkInside && (looksSpoken(text, firstPerson) || inSpeech && continuesSpeech(text))
        if (!dashed && quoted == null && !remarkInside && !spoken) return null
        if (spoken) return listOf(text to true)
        if (quoted != null) {
            val cut = quoted.range.last + 1
            return listOf(text.substring(0, cut) to true, text.substring(cut) to false).filter { it.first.isNotEmpty() }
        }
        // Alternating speech / remark separated by dashes after punctuation; a continuation starts as speech too.
        val out = mutableListOf<Pair<String, Boolean>>()
        var start = 0; var speech = true
        for (m in turn.findAll(text)) {
            if (m.range.first <= start) continue
            out += text.substring(start, m.range.first) to speech
            start = m.range.first; speech = !speech
        }
        out += text.substring(start) to speech
        return out
    }

    private val gerund = Regex("^[а-яё]{3,}(в|вши|вшись)$")
    private val narrator = setOf("я", "мы", "меня", "мне", "мной", "нас", "нам", "нами", "мой", "моя", "моё", "мое", "наш", "наша", "наше")

    /** The start of a remark after a dash: a past-tense verb, a gerund («ответив»), он / она, or a character's name. */
    private fun looksLikeRemark(after: String, cast: List<Person>): Boolean {
        val words = word.findAll(after.lowercase()).map { it.value }.take(4).toList()
        if (words.any { pastVerb.matches(it) || gerund.matches(it) || it == "он" || it == "она" }) return true
        return cast.any { p -> p.forms.any { f -> f.lowercase() in words } }
    }

    /** A reply without a dash, a quote or a remark (Readest drops the dash): only clear spoken language — ! or ? at the
     * end, talking to someone (ты / вы…), an interjection, the polite «-с». «я / мне / мой» and a final «…» are not
     * enough: first-person narration is full of them, and every such sentence used to get a man's reply voice. */
    private fun looksSpoken(text: String, firstPerson: Boolean): Boolean {
        val t = text.trim()
        if (t.endsWith("!") || t.endsWith("?") || t.endsWith("?!") || t.endsWith("!..") || t.endsWith("?..")) return true
        val words = word.findAll(t.lowercase()).map { it.value }.toList()
        if (words.any { it in secondPerson || it in interjections }) return true
        return Regex("[А-Яа-яЁё]-с(?![А-Яа-яЁё])").containsMatchIn(t)
    }
    private val secondPerson = setOf("ты", "вы", "вас", "вам", "вами", "тебя", "тебе", "тобой", "ваш", "ваша", "ваше", "ваши",
        "твой", "твоя", "твоё", "твое", "твои")

    private val interjections = setOf("гм", "ну", "эх", "ах", "ох", "ей", "эй", "ой", "ага", "хе", "ха", "фью", "тьфу", "вишь", "эка", "эвона")
    private val pastVerb = Regex("^[а-яё]{2,}(л|ла|ло|ли|лся|лась|лось|лись)$")

    /** A sentence after speech, without a dash of its own (Readest sends a long reply sentence by sentence): the reply
     * goes on only with signs of spoken language (я/вы, «-с», «гм», ! ? …). A plain sentence is the author's: on doubt
     * the narrator's voice, not a character's (a present-tense narration sentence used to stay with the speaker). */
    private fun continuesSpeech(text: String): Boolean {
        val t = text.trim()
        if (t.endsWith("!") || t.endsWith("?") || t.endsWith("…") || t.endsWith("..") || t.endsWith("?!")) return true
        val words = word.findAll(t.lowercase()).map { it.value }.toList()
        if (words.any { it in addressing || it in interjections }) return true
        return Regex("[А-Яа-яЁё]-с(?![А-Яа-яЁё])").containsMatchIn(t)
    }

    private fun fromRemark(remark: String, cast: List<Person>, firstPerson: Boolean): Speaker? {
        val words = word.findAll(remark).map { it.value }.toList()
        val lower = words.map { it.lowercase() }
        val phrase = " " + lower.joinToString(" ") + " "
        for (person in cast) {
            // One-word forms by word, several-word forms («Настасья Филипповна») as a phrase.
            if (person.forms.any { form -> val f = word.findAll(form.lowercase()).joinToString(" ") { it.value }
                    f.isNotEmpty() && " $f " in phrase }) return Speaker(role(person.gender), person.id)
        }
        if (firstPerson && "я" in lower) return Speaker(VoiceRole.AUTHOR, null)
        // The verb or pronoun nearest the dash: «ответил сосед», «сказала она», «— и, ответив, молодой человек оглядел».
        for (w in lower.take(8)) {
            if (w == "он") return Speaker(VoiceRole.MALE, null)
            if (w == "она") return Speaker(VoiceRole.FEMALE, null)
            if (femaleVerb.matches(w)) return Speaker(VoiceRole.FEMALE, null)
            if (maleVerb.matches(w)) return Speaker(VoiceRole.MALE, null)
        }
        return null
    }

    private fun role(gender: String) = when (gender) { "f" -> VoiceRole.FEMALE; else -> VoiceRole.MALE }

    /** A line without a remark: the other known participant; with one known, a man answers a woman and a man answers a
     * man (most classic dialogues are between men — a guessed woman's voice there sounds wrong). */
    private fun next(): Speaker {
        val last = recent.lastOrNull() ?: return Speaker(VoiceRole.MALE, null)
        return recent.lastOrNull { it != last } ?: if (last.character == null) Speaker(VoiceRole.MALE, null) else last
    }

    private fun remember(s: Speaker) {
        if (recent.lastOrNull() == s) return
        recent.remove(s); recent.addLast(s)
        while (recent.size > 2) recent.removeFirst()
    }

    private fun merge(parts: List<VoiceRoleText>): List<VoiceRoleText> {
        val out = mutableListOf<VoiceRoleText>()
        for (p in parts) {
            if (p.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.sameVoice(p)) out[out.lastIndex] = last.copy(text = last.text + p.text) else out += p
        }
        return out
    }
}
