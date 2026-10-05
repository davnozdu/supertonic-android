package com.brahmadeo.supertonic.tts.tera

import org.junit.Assert.assertEquals
import org.junit.Test

class TeraDurationCapTest {
    @Test fun shortRepliesLoseThePredictorFloor() {
        assertEquals(.55f, TeraDurationCap.seconds(1.03f, "Да."), 1e-4f)
        assertEquals(.95f, TeraDurationCap.seconds(1.19f, "Хорошо."), 1e-4f)
        assertEquals(1.15f, TeraDurationCap.seconds(1.26f, "Иди сюда!"), 1e-4f)
    }

    @Test fun naturalDurationsAndLongPhrasesStayUntouched() {
        assertEquals(.40f, TeraDurationCap.seconds(.40f, "Да."), 1e-6f)
        assertEquals(1.97f, TeraDurationCap.seconds(1.97f, "Он сказал, что вернётся."), 1e-6f)
        assertEquals(4.23f, TeraDurationCap.seconds(4.23f, "Когда поезд наконец остановился, на перроне уже никого не было."), 1e-6f)
        assertEquals(1.0f, TeraDurationCap.seconds(1.0f, "Хм."), 1e-6f)
        assertEquals(1.03f, TeraDurationCap.seconds(1.03f, "Да.", enabled = false), 1e-6f)
    }

    @Test fun explicitStressMarksAreNotVowels() {
        assertEquals(1, TeraDurationCap.vowels("д+а́."))
    }
}
