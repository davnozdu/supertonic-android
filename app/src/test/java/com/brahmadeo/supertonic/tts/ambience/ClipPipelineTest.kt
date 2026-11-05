package com.brahmadeo.supertonic.tts.ambience

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ClipPipelineTest {
    @Test fun keepsOrderAndPropagatesProducerFailure() {
        val consumed = ArrayList<Int>()
        val failure = IllegalStateException("producer failed")
        try {
            ClipPipeline(listOf(1, 2, 3), { if (it == 3) throw failure else it }, consumed::add).run()
            fail("producer failure lost")
        } catch (e: IllegalStateException) { assertSame(failure, e) }
        assertEquals(listOf(1, 2), consumed)
    }

    @Test fun interruptedCallerStillWaitsForNativeProducer() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val interrupt = AtomicReference(false)
        val caller = Thread {
            try {
                ClipPipeline(listOf(1, 2), { n ->
                    if (n == 2) { entered.countDown(); awaitIgnoringInterrupt(release) }
                    n
                }, {
                    check(entered.await(2, TimeUnit.SECONDS))
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("decoder failed")
                }).run()
            } catch (t: Throwable) { failure.set(t) }
            finally { interrupt.set(Thread.interrupted()); returned.countDown() }
        }
        caller.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse("returned while native producer still owns graphs", returned.await(200, TimeUnit.MILLISECONDS))
        } finally { release.countDown(); caller.join(3000) }
        assertFalse(caller.isAlive)
        assertTrue(failure.get() is IllegalStateException)
        assertTrue("interrupt must be restored", interrupt.get())
    }

    @Test fun producerDoesNotReenterFullQueueAfterCancellation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val caller = Thread {
            try {
                ClipPipeline(listOf(1, 2, 3, 4), { n ->
                    worker.set(Thread.currentThread())
                    if (n == 4) { entered.countDown(); awaitIgnoringInterrupt(release) }
                    n
                }, {
                    check(entered.await(2, TimeUnit.SECONDS))
                    throw IllegalStateException("consumer stopped with full queue")
                }).run()
            } catch (_: IllegalStateException) {} finally { returned.countDown() }
        }
        caller.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue("producer tried to put into abandoned full queue", returned.await(2, TimeUnit.SECONDS))
        } finally { release.countDown(); worker.get()?.interrupt(); caller.join(3000) }
        assertFalse(caller.isAlive)
    }

    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (latch.count > 0) try { latch.await() } catch (_: InterruptedException) {}
    }
}
