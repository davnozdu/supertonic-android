package com.brahmadeo.supertonic.tts.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookVoiceBindingTest {
    private fun ch(id: String, name: String, gender: String, vararg forms: String) =
        BookPackage.Character(id, name, gender, 5, 50, forms.toList(), null)

    // «Идиот» as first prepared and as regenerated on 13 October: other ids and another order of the cast.
    private val first = listOf(ch("myshkin", "Мышкин", "m", "князь Мышкин", "Мышкин", "Лев Николаевич"),
        ch("ganya", "Ганя", "m", "Ганя", "Гаврила Ардалионович", "Иволгин"),
        ch("general_ivolgin", "генерал Иволгин", "m", "генерал Иволгин", "Ардалион Александрович"),
        ch("ivan_fedorovich_epanchin", "Иван Фёдорович", "m", "Иван Фёдорович", "генерал Епанчин"),
        ch("nastasya_filippovna", "Настасья Филипповна", "f", "Настасья Филипповна", "Настасьи Филипповны"))
    private val regenerated = listOf(ch("nastasya_filippovna", "Настасья Филипповна", "f", "Настасья Филипповна"),
        ch("myshkin", "князь Мышкин", "m", "Мышкин"),
        ch("epanchin_ivan", "Епанчин", "m", "генерал Епанчин", "Иван Федорович"),
        ch("gavrila_ardalionovich_ivolgin", "Гаврила Ардалионович", "m", "Гаврила Ардалионович", "Ганя", "Иволгин"),
        ch("general_ivolgin", "Ардалион Иволгин", "m", "Ардалион Александрович", "Иволгин"),
        ch("ferdychenko", "Фердыщенко", "m", "Фердыщенко"))
    private val voices = mapOf("myshkin" to "ru_igor", "ganya" to "ru_bogdan", "general_ivolgin" to "ru_marat",
        "ivan_fedorovich_epanchin" to "ru_eduard", "nastasya_filippovna" to "ru_saida")

    @Test fun aRegeneratedCastKeepsEveryVoice() {
        val fixed = BookVoiceBinding().with(first, voices)!!.withRole("author", "ru_roman")!!
        val again = BookVoiceBinding.parse(fixed.json())
        assertEquals(fixed, again)
        val resolved = again.resolve(regenerated)
        assertEquals("ru_igor", resolved["myshkin"])
        assertEquals("ru_saida", resolved["nastasya_filippovna"])
        // New ids found by their names; the two Иволгины keep their own voices.
        assertEquals("ru_bogdan", resolved["gavrila_ardalionovich_ivolgin"])
        assertEquals("ru_marat", resolved["general_ivolgin"])
        assertEquals("ru_eduard", resolved["epanchin_ivan"])
        assertNull(resolved["ferdychenko"])
        assertEquals("ru_roman", again.roles["author"])
    }

    @Test fun onlyNewOrUnusableVoicesAreFixedAgain() {
        val fixed = BookVoiceBinding().with(first, voices)!!
        // The same assignment again: nothing to save.
        assertNull(fixed.with(first, voices))
        // A new character is added, the others stay.
        val next = fixed.with(regenerated, mapOf("ferdychenko" to "ru_gamat", "myshkin" to "ru_roman"))!!
        assertEquals("ru_gamat", next.characters.getValue("ferdychenko").voice)
        assertEquals("ru_igor", next.characters.getValue("myshkin").voice)
        // A voice of a model no longer chosen is replaced.
        val moved = fixed.with(first, mapOf("myshkin" to "teratts_v2::ru_m1")) { it.startsWith("teratts_v2::") }!!
        assertEquals("teratts_v2::ru_m1", moved.characters.getValue("myshkin").voice)
        assertNull(fixed.withRole("author", "ru_roman")?.withRole("author", "ru_roman"))
    }

    @Test fun brokenStorageStartsEmpty() {
        assertEquals(BookVoiceBinding(), BookVoiceBinding.parse(null))
        assertEquals(BookVoiceBinding(), BookVoiceBinding.parse("{oops"))
    }
}
