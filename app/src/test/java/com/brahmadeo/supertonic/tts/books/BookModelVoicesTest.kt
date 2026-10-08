package com.brahmadeo.supertonic.tts.books

import org.junit.Assert.*
import org.junit.Test

class BookModelVoicesTest {
    @Test fun everyBundledModelVoiceHasAnExplicitGender() {
        val models = mapOf(
            "teratts_v2" to listOf("ru_f1","ru_f2","ru_m1","ru_m5","eng_f3","eng_f4_whisper","eng_f5","eng_m2_whisper","eng_m3","eng_m4"),
            "silero_v5_5_ru" to listOf("aidar","baya","kseniya","eugene","xenia"),
            "silero_cis_ru" to listOf("ru_aigul","ru_albina","ru_alexandr","ru_alfia","ru_alfia2","ru_bogdan","ru_dmitriy","ru_ekaterina","ru_vika","ru_gamat","ru_igor","ru_karina","ru_kejilgan","ru_kermen","ru_marat","ru_miyau","ru_nurgul","ru_oksana","ru_onaoy","ru_ramilia","ru_roman","ru_safarhuja","ru_saida","ru_sibday","ru_zara","ru_zhadyra","ru_zhazira","ru_zinaida","ru_eduard"),
            "kokoro_ru_v2" to listOf("sveta","masha","dima"),
            "shtorm_pocket_ru" to listOf("alba","marius","javert","jean","fantine","cosette","eponine"))
        for ((model, names) in models) for (name in names) assertTrue(BookVoiceAssign.gender(BookVoiceRef(model, name).key) in listOf("m", "f"))
        assertEquals(54, models.values.sumOf { it.size })
        for (name in listOf("ru_miyau", "ru_onaoy", "ru_sibday")) assertEquals("m", BookVoiceAssign.gender(name))
    }
    @Test fun voiceIdentityIncludesModelAndRejectsPaths() {
        assertEquals("sveta", BookVoiceRef.parse("kokoro_ru_v2::sveta")!!.voice)
        for (bad in listOf("sveta", "no_engine::sveta", "kokoro_ru_v2::../sveta", "kokoro_ru_v2::sveta::dima")) assertNull(BookVoiceRef.parse(bad))
        assertEquals("f", BookVoiceAssign.gender("kokoro_ru_v2::sveta"))
        assertEquals("m", BookVoiceAssign.gender("silero_cis_ru::ru_roman"))
    }
    @Test fun redistributionUsesSeveralModelsAndKeepsNarratorAndOthersSeparate() {
        val groups = listOf(listOf("teratts_v2::ru_m1", "teratts_v2::ru_f1", "teratts_v2::ru_m5", "teratts_v2::ru_f2"),
            listOf("silero_v5_5_ru::aidar", "silero_v5_5_ru::baya", "silero_v5_5_ru::eugene", "silero_v5_5_ru::kseniya"),
            listOf("silero_cis_ru::ru_roman", "silero_cis_ru::ru_zinaida", "silero_cis_ru::ru_eduard", "silero_cis_ru::ru_aigul"))
        val voices = BookVoiceRef.interleave(groups)
        val chars = List(4) { i -> BookPackage.Character("c$i", "c$i", "m", 20-i, 50, listOf("c$i"), null) }
        val out = BookVoiceAssign.assign(BookPackage.Cast(listOf("s1"), chars, emptyList()), voices, "teratts_v2::ru_m1")
        assertEquals(3, out.characters.values.map { BookVoiceRef.parse(it)!!.model }.toSet().size)
        assertTrue("teratts_v2::ru_m1" !in out.characters.values)
        assertTrue(out.male != null && out.male !in out.characters.values)
        assertEquals("f", BookVoiceAssign.gender(out.female!!))
    }
    @Test fun manualModelChoiceWinsWithoutStealingItsVoice() {
        val voices = listOf("teratts_v2::ru_m1", "silero_v5_5_ru::aidar", "silero_cis_ru::ru_roman", "silero_cis_ru::ru_eduard")
        val chars = List(2) { i -> BookPackage.Character("c$i", "c$i", "m", 20, 50, emptyList(), null) }
        val out = BookVoiceAssign.assign(BookPackage.Cast(listOf("s1"), chars, emptyList()), voices, "teratts_v2::ru_m1", mapOf("c0" to "silero_cis_ru::ru_roman"))
        assertEquals("silero_cis_ru::ru_roman", out.characters["c0"])
        assertTrue(out.characters["c1"] != out.characters["c0"])
    }
    @Test fun manualGenderOverridesDefaultsAndUnknownIsNotAssigned() {
        val voices = listOf("teratts_v2::ru_m1", "teratts_v2::ru_f1", "silero_v5_5_ru::baya", "kokoro_ru_v2::masha", "silero_cis_ru::ru_roman")
        val cast = BookPackage.Cast(listOf("s1"), listOf(BookPackage.Character("a", "a", "m", 20, 50, emptyList(), null)), emptyList())
        val out = BookVoiceAssign.assign(cast, voices, "teratts_v2::ru_m1", genders = mapOf(
            "teratts_v2::ru_f1" to "m", "silero_v5_5_ru::baya" to null, "silero_cis_ru::ru_roman" to "f"))
        // The only male voice is kept for others, so no mismatched/default/unknown voice is used.
        assertTrue(out.characters.isEmpty())
        assertEquals("teratts_v2::ru_f1", out.male)
        assertTrue(out.female in listOf("kokoro_ru_v2::masha", "silero_cis_ru::ru_roman"))
    }
    @Test fun bookContextCarriesQualifiedRolesAndDistinctCacheKeys() {
        val cast = BookPackage.Cast(listOf("s1"), emptyList(), emptyList())
        val a = BookContext(1, "s1", cast, mapOf("x" to "teratts_v2::ru_m1"))
        val b = BookContext(1, "s1", cast, mapOf("x" to "silero_cis_ru::ru_roman"))
        assertTrue(a.key != b.key)
        assertEquals("silero_cis_ru::ru_roman", b.voiceOf(com.brahmadeo.supertonic.tts.llm.VoiceRole.MALE, "x"))
    }
}
