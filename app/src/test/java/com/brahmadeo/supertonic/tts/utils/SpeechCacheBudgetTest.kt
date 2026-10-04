package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.*
import org.junit.Test

class SpeechCacheBudgetTest {
    private val mb=1024L*1024L
    @Test fun configured256MbIsFullyAvailableOnThe512MbDeviceHeap() {
        assertEquals(256*mb,SpeechCacheBudget.limit(256,512*mb))
    }
    @Test fun impossibleGigabyteChoiceCannotExhaustManagedHeap() {
        assertEquals(384*mb,SpeechCacheBudget.limit(1024,512*mb))
        assertEquals(64*mb,SpeechCacheBudget.limit(1024,128*mb))
    }
}
