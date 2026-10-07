package com.brahmadeo.supertonic.tts.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlaybackBufferTest {
    @Test fun cancelWakesSenderParkedOnFullChannel() {
        val channel = Channel<Int>(2)
        repeat(2) { channel.trySend(it) }
        var result: Boolean? = null
        val thread = Thread { result = PlaybackBuffer.sendBlocking(channel, 3) }.apply { start() }
        thread.join(200)
        assertTrue("send must wait while the channel is full", thread.isAlive)
        channel.cancel()
        thread.join(2000)
        assertFalse(thread.isAlive)
        assertEquals(false, result)
        assertFalse(PlaybackBuffer.sendBlocking(channel, 4))
    }

    /** Service structure: the consumer stops reading, the synthesis callback is parked on a full channel. */
    @Test fun stopCompletesWhenChannelIsCancelledBeforeJoin() = runBlocking {
        val channel = Channel<Int>(PlaybackBuffer.CAPACITY)
        val parked = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            launch { awaitCancellation() }
            try {
                repeat(PlaybackBuffer.CAPACITY) { channel.send(it) }
                parked.countDown()
                PlaybackBuffer.sendBlocking(channel, -1)
            } finally { channel.close() }
        }
        assertTrue(parked.await(2, TimeUnit.SECONDS))
        channel.cancel()
        assertNotNull(withTimeoutOrNull(2000) { job.cancelAndJoin(); true })
    }

    @Test fun preRollOpensByQueuedPacketsBeforeChannelFills() = runBlocking {
        val channel = Channel<Int>(PlaybackBuffer.CAPACITY)
        val gate = PreRollGate.forChannel(sentences = 5, sampleRate = 24000)
        var received = 0
        val consumer = launch(Dispatchers.IO) { gate.await(); for (p in channel) received++ }
        val producer = launch(Dispatchers.IO) {
            // One long sentence: far more small packets than the channel holds, gate by sentences never opens.
            repeat(PlaybackBuffer.CAPACITY * 2) { gate.queued(100); channel.send(it) }
            channel.close()
        }
        withTimeout(5000) { producer.join(); consumer.join() }
        assertEquals(PlaybackBuffer.CAPACITY * 2, received)
        assertTrue(gate.isOpen)
    }

    @Test fun preRollThresholds() {
        val gate = PreRollGate(sentences = 2, maxPackets = 10, maxBytes = 1000)
        gate.sentenceDone(1); repeat(9) { gate.queued(10) }
        assertFalse(gate.isOpen)
        gate.sentenceDone(2)
        assertTrue(gate.isOpen)

        val byPackets = PreRollGate(5, 10, Long.MAX_VALUE)
        repeat(9) { byPackets.queued(1) }; assertFalse(byPackets.isOpen)
        byPackets.queued(1); assertTrue(byPackets.isOpen)

        val byBytes = PreRollGate(5, 1000, 48000L * 2 * 60)
        repeat(59) { byBytes.queued(96000) }; assertFalse(byBytes.isOpen)
        byBytes.queued(96000); assertTrue(byBytes.isOpen)
    }
}
