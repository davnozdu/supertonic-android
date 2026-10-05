package com.brahmadeo.supertonic.tts.tera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeraVocoderChunksTest {
    @Test fun windowsCoverEveryFrameOnceWithFullLeftContext() {
        for (frames in 1..400) {
            val windows = TeraVocoderChunks.plan(frames)
            assertEquals(0, windows.first().start)
            assertEquals(frames, windows.last().end)
            windows.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
            windows.forEach { w ->
                assertTrue(w.end > w.start)
                assertEquals(minOf(w.start, TeraVocoderChunks.CONTEXT), w.start - w.contextStart)
            }
        }
    }

    @Test fun firstAudioStaysShortAndLaterWindowsAreLarger() {
        val windows = TeraVocoderChunks.plan(127)
        assertEquals(TeraVocoderChunks.Window(0, 0, 16), windows[0])
        assertEquals(TeraVocoderChunks.Window(0, 16, 80), windows[1])
        assertEquals(TeraVocoderChunks.Window(60, 80, 127), windows[2])
        assertEquals(listOf(TeraVocoderChunks.Window(0, 0, 9)), TeraVocoderChunks.plan(9))
    }
}
