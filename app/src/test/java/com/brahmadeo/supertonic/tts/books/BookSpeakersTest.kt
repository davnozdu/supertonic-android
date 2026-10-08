package com.brahmadeo.supertonic.tts.books

import com.brahmadeo.supertonic.tts.llm.VoiceRole
import com.brahmadeo.supertonic.tts.llm.VoiceRoleProtocol
import com.brahmadeo.supertonic.tts.llm.VoiceRoleText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSpeakersTest {
    private val cis = listOf("ru_aigul", "ru_albina", "ru_alexandr", "ru_alfia", "ru_alfia2", "ru_bogdan", "ru_dmitriy",
        "ru_ekaterina", "ru_vika", "ru_gamat", "ru_igor", "ru_karina", "ru_kejilgan", "ru_kermen", "ru_marat", "ru_miyau",
        "ru_nurgul", "ru_oksana", "ru_onaoy", "ru_ramilia", "ru_roman", "ru_safarhuja", "ru_saida", "ru_sibday", "ru_zara",
        "ru_zhadyra", "ru_zhazira", "ru_zinaida", "ru_eduard")

    private fun character(id: String, gender: String, speaker: Int, hint: String? = null, mentions: Int = 10) =
        BookPackage.Character(id, id, gender, speaker, mentions, listOf(id), hint)

    @Test fun narratorFirstThenCharactersThenOthersFromTheRest() {
        val cast = BookPackage.Cast(listOf("s1"), listOf(
            character("a", "m", 50, hint = "ru_igor"),
            character("b", "m", 40),                       // no hint: must not take ru_igor or ru_roman
            character("c", "m", 30, hint = "ru_roman"),
            character("d", "f", 20, hint = "ru_dmitriy"),  // the narrator's voice (and male): ignored
            character("e", "m", 0, mentions = 3),          // minor: «прочие»
            character("f", "?", 90),                       // unknown gender: «прочие»
            character("g", "f", 1, mentions = 40),         // chosen by hand
        ), emptyList())
        val result = BookVoiceAssign.assign(cast, cis, author = "ru_dmitriy", manual = mapOf("g" to "ru_zinaida"))
        val voices = result.characters
        assertEquals("ru_igor", voices["a"]); assertEquals("ru_roman", voices["c"]); assertEquals("ru_zinaida", voices["g"])
        assertTrue(voices["b"] !in setOf("ru_igor", "ru_roman") && BookVoiceAssign.gender(voices["b"]!!) == "m")
        assertEquals("f", BookVoiceAssign.gender(voices["d"]!!))
        assertNull(voices["e"]); assertNull(voices["f"])
        assertTrue("ru_dmitriy" !in voices.values)
        // «прочие» take what is left: their own gender, not the narrator, not any character's voice.
        assertEquals("m", BookVoiceAssign.gender(result.male!!)); assertEquals("f", BookVoiceAssign.gender(result.female!!))
        assertTrue(result.male !in voices.values && result.female !in voices.values && result.male != "ru_dmitriy")
    }

    @Test fun othersKeepOneVoiceEvenWhenCharactersWantAll() {
        val men = (1..20).map { character("m$it", "m", 100 - it) }
        val result = BookVoiceAssign.assign(BookPackage.Cast(listOf("s1"), men, emptyList()), cis, author = "ru_dmitriy")
        val male = cis.count { BookVoiceAssign.gender(it) == "m" } - 1  // without the narrator
        assertEquals(male - 1, result.characters.size)                    // one left for the «прочие»
        assertTrue(result.male != null && result.male !in result.characters.values)
    }

    @Test fun teraAndUnknownVoiceGenders() {
        assertEquals("m", BookVoiceAssign.gender("ru_m5")); assertEquals("f", BookVoiceAssign.gender("ru_f2"))
        assertNull(BookVoiceAssign.gender("narrator_x"))
    }

    @Test fun speakerMustBeOfTheSectionAndOfTheRoleGender() {
        val cast = BookPackage.Cast(listOf("s1"), listOf(character("myshkin", "m", 9), character("aglaya", "f", 5)), emptyList())
        val book = BookContext(1, "s1", cast, mapOf("myshkin" to "ru_alexandr", "aglaya" to "ru_albina"))
        // units: 0:— 1:Да, 2:— 3:сказал 4:князь. 5:— 6:Нет! 7:— 8:Нет. 9:Ещё
        val text = "— Да, — сказал князь. — Нет! — Нет. Ещё"
        fun answer(vararg segments: String) = """{"paragraphs":[{"id":0,"segments":[${segments.joinToString(",")}]}]}"""
        val plan = VoiceRoleProtocol.parseValidated(answer(
            """{"start":0,"end":2,"role":"male","confidence":"clear","speaker":"myshkin"}""",
            """{"start":2,"end":5,"role":"author","confidence":"clear","speaker":"myshkin"}""",
            """{"start":5,"end":7,"role":"male","confidence":"clear","speaker":"aglaya"}""",
            """{"start":7,"end":9,"role":"male","confidence":"clear","speaker":"stranger"}""",
            """{"start":9,"end":10,"role":"female","confidence":"clear","speaker":"aglaya"}"""), listOf(text), book).single()!!
        assertEquals(text, plan.joinToString("") { it.text })
        assertEquals(VoiceRoleText("— Да, ", VoiceRole.MALE, "myshkin", "ru_alexandr"), plan[0])
        assertNull(plan[1].character)                       // author never has a speaker
        assertEquals(VoiceRole.MALE, plan[2].role); assertNull(plan[2].character) // aglaya is not male; stranger unknown
        assertEquals(VoiceRoleText("Ещё", VoiceRole.FEMALE, "aglaya", "ru_albina"), plan[3])
        // Without a book nothing changes: plain roles as before.
        assertTrue(VoiceRoleProtocol.parseValidated(answer(
            """{"start":0,"end":10,"role":"male","confidence":"clear","speaker":"myshkin"}"""), listOf(text)).single()!!.all { it.character == null })
    }
}
