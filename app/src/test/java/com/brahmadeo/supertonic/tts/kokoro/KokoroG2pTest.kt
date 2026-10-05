package com.brahmadeo.supertonic.tts.kokoro

import org.junit.Assert.*
import org.junit.Test

class KokoroG2pTest {
    @Test fun keepsExplicitStressAndYoDuringOrthoepicRespelling() {
        assertEquals("большо́во до́рога коне́шно чу́ство ёлка", KokoroG2p.marked("больш+ого д+орого кон+ечно ч+увство ёлка"))
        assertEquals("за́мок замо́к", KokoroG2p.marked("за́мок замо́к"))
    }
    @Test fun reductionMatchesUpstreamExamplesAndKeepsPrimaryStress() {
        assertEquals("zˈɑmək", KokoroG2p.normalizeIpa("zˈɑmʌk"))
        assertEquals("zɐmˈok", KokoroG2p.normalizeIpa("zamˈok"))
        assertEquals("svʲˈetlə", KokoroG2p.normalizeIpa("svʲˈetɭʌ"))
        assertEquals("svʲitlˈo", KokoroG2p.normalizeIpa("svʲitɭˈo"))
        assertEquals("ɕː", KokoroG2p.normalizeIpa("ɕ"))
        assertEquals("ˈɑʦə", KokoroG2p.normalizeIpa("ˈɑt^sˌʌ"))
    }
    @Test fun sourcePunctuationDoesNotDisappearInNativePhonemizer() {
        val calls = mutableListOf<String>()
        val ipa = KokoroG2p.phonemize("Да, «готов?» Ёлка!", { calls.add(it); "ˈɑ" })
        assertEquals(listOf("да", "готов", "ёлка"), calls)
        assertEquals("ˈɑ,“ ˈɑ?” ˈɑ!", ipa)
    }
    @Test fun boundedChunksRetainAllWordsWithoutSplittingAPhonemeWord() {
        val ipa = List(80) { "zɐmˈok" }.joinToString(" ")
        val chunks = KokoroG2p.chunks(ipa, 100)
        assertTrue(chunks.all { it.length <= 100 })
        assertEquals(ipa, chunks.joinToString(" "))
    }
    @Test(expected = IllegalArgumentException::class) fun neverSilentlyDropsAnOverlongWord() {
        KokoroG2p.chunks("a".repeat(511))
    }
}
