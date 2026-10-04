package com.brahmadeo.supertonic.tts.music

import org.junit.Assert.*
import org.junit.Test

class MusicReadingStateTest {
    @Test fun queuedParagraphsKeepMusicUntilActualPlaybackEnds() {
        val state=MusicReadingState()
        state.enqueue("reader","one",false)
        state.enqueue("reader","two",false)
        assertFalse(state.playing()) // LLM preparation is silent.
        state.start("reader","one")
        assertTrue(state.playing())
        state.finish("reader","one",true)
        assertTrue(state.playing()) // No audible restart between queued paragraphs.
        state.start("reader","two")
        state.finish("reader","two",true)
        assertFalse(state.playing())
    }
    @Test fun pauseAndLateCallbacksCannotRestartMusic() {
        val state=MusicReadingState()
        state.enqueue("reader","one",false);state.start("reader","one")
        state.stop("reader")
        state.start("reader","one");state.finish("reader","one",true)
        assertFalse(state.playing())
        state.enqueue("reader","two",false)
        assertFalse(state.playing())
        state.start("reader","two")
        assertTrue(state.playing())
    }
    @Test fun canceledUnstartedDuplicateDoesNotConsumePlayingUtterance() {
        val state=MusicReadingState()
        state.enqueue("reader",null,false);state.start("reader",null)
        state.enqueue("reader",null,false)
        state.finish("reader",null,false)
        assertTrue(state.playing())
        state.finish("reader",null,true)
        assertFalse(state.playing())
    }
    @Test fun fileSynthesisAndRejectedRequestsDoNotStartMusic() {
        val state=MusicReadingState()
        state.start("reader","file");state.finish("reader","file",true)
        assertFalse(state.playing())
        val token=state.enqueue("reader","rejected",false)
        state.reject("reader",token);state.start("reader","rejected")
        assertFalse(state.playing())
    }
    @Test fun flushingQueueWaitsForNewAudioAndIgnoresOldStop() {
        val state=MusicReadingState()
        state.enqueue("reader","same",false);state.start("reader","same")
        state.enqueue("reader","same",true)
        state.finish("reader","same",true)
        assertFalse(state.playing())
        state.start("reader","same")
        assertTrue(state.playing())
    }
    @Test fun readersAndBuiltinPlaybackAreIndependent() {
        val state=MusicReadingState()
        state.enqueue("a","one",false);state.start("a","one")
        state.enqueue("b","two",false);state.start("b","two")
        state.stop("a");assertTrue(state.playing())
        state.app(true);state.stopTts();assertTrue(state.playing())
        state.app(false);assertFalse(state.playing())
    }
}
