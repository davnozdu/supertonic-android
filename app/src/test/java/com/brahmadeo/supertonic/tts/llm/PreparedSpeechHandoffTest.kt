package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.*
import org.junit.Test

class PreparedSpeechHandoffTest {
    @Test fun llmProvenanceTravelsWithItsTextAndNeverLeaksToFallback() {
        val handoff=PreparedSpeechHandoff()
        handoff.put(handoff.token(),"same","светло́",true)
        handoff.put(handoff.token(),"same","светло",false)
        assertEquals(PreparedSpeechText("светло́",true),handoff.takePrepared("same"))
        assertEquals(PreparedSpeechText("светло",false),handoff.takePrepared("same"))
        assertNull(handoff.takePrepared("same"))
        val old=handoff.token()
        handoff.clear()
        assertFalse(handoff.put(old,"same","светло́",true))
        assertNull(handoff.takePrepared("same"))
    }
    @Test fun deliveryIsExactAndConsumedOnce() {
        val handoff=PreparedSpeechHandoff()
        assertTrue(handoff.put(handoff.token(),"source","prepared"))
        assertNull(handoff.take("different"))
        assertEquals("prepared",handoff.take("source"))
        assertNull(handoff.take("source"))
    }
    @Test fun repeatedRequestsRetainTheirOwnPreparedContext() {
        val handoff=PreparedSpeechHandoff()
        handoff.put(handoff.token(),"same","first")
        handoff.put(handoff.token(),"same","second")
        assertEquals("first",handoff.take("same"))
        assertEquals("second",handoff.take("same"))
    }
    @Test fun stopOrSettingsChangeRejectsLateWorkerResults() {
        val handoff=PreparedSpeechHandoff()
        val previous=handoff.token()
        handoff.put(previous,"source","old")
        handoff.clear()
        assertFalse(handoff.put(previous,"source","late"))
        assertNull(handoff.take("source"))
        assertTrue(handoff.put(handoff.token(),"source","new"))
        assertEquals("new",handoff.take("source"))
    }
    @Test fun memoryIsBounded() {
        val handoff=PreparedSpeechHandoff(2)
        for(i in 1..3) handoff.put(handoff.token(),"$i","$i")
        assertNull(handoff.take("1"))
        assertEquals("2",handoff.take("2"))
        assertEquals("3",handoff.take("3"))
    }
}
