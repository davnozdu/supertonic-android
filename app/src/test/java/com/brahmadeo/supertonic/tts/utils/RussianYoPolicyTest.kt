package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class RussianYoPolicyTest {
    @Test fun disabledRestorationKeepsSourceYo() {
        assertEquals("Реб+енок, берёза и ков+ер.", RussianYoPolicy.apply("Ребенок, берёза и ковер.","Реб+ёнок, берёза и ков+ёр.",false))
    }
    @Test fun uppercaseAndCombiningStressArePreserved() {
        assertEquals("ЗЕЛЕ\u0301НЫЙ, ЁЛКА.", RussianYoPolicy.apply("ЗЕЛЕНЫЙ, ЁЛКА.","ЗЕЛЁ\u0301НЫЙ, ЁЛКА.",false))
    }
    @Test fun enabledRetainsDictionaryYo() {
        assertEquals("реб+ёнок", RussianYoPolicy.apply("ребенок","реб+ёнок",true))
    }
    @Test fun wallsNeverAcquireYo() {
        assertEquals("По ст+енам и у ст+ены.", RussianYoPolicy.apply("По стенам и у стены.","По ст+ёнам и у ст+ёны.",true))
        assertEquals("по стенам", RussianYoPolicy.apply("по стёнам","по стёнам",true))
    }
}
