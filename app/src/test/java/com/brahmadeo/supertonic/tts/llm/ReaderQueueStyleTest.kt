package com.brahmadeo.supertonic.tts.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderQueueStyleTest {
    @Test fun oneSentenceAtATimeReadersAreRecognisedQueueingOnesNot() {
        val style = ReaderQueueStyle()
        // Readest: every sentence flushes the queue.
        style.record(1001, flush = true)
        assertFalse(style.oneAtATime(1001)) // one utterance proves nothing (Moon+ also starts with a flush)
        style.record(1001, flush = true)
        assertTrue(style.oneAtATime(1001))
        // Moon+: a flush, then utterances queued behind it; later restarts flush again.
        style.record(1002, flush = true); style.record(1002, flush = false); style.record(1002, flush = true)
        assertFalse(style.oneAtATime(1002))
        assertFalse(style.oneAtATime(9999))
    }
}
