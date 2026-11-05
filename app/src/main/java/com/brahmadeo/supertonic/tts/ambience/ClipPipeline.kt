package com.brahmadeo.supertonic.tts.ambience

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Two stages of one list of clips, each in its own thread: [produce] — the text encoder and the DiT steps, the latter
 * on the NPU — and [consume] — the decoder, loudness and the file, on the CPU. While the NPU makes the next clip the
 * CPU decodes the previous one, so neither device waits for the other (before, the NPU idled during the encoder and
 * the decoder, and the CPU idled through every DiT step).
 *
 * Two clips can be in flight: the producer stops putting when the queue is full, so at most two sets of latents
 * (~330 KB each for 30 s) wait for the decoder. Anything thrown on either side stops both stages and is rethrown here
 * (an [InterruptedException] from the cancellation check included), so the caller's `finally` closes the graphs.
 */
class ClipPipeline<T, R>(private val items: List<T>, private val produce: (T) -> R, private val consume: (R) -> Unit) {
    fun run() {
        if (items.isEmpty()) return
        val queue = ArrayBlockingQueue<R>(2)
        val failure = AtomicReference<Throwable?>(null)
        val stopped = AtomicBoolean(false)
        val done = AtomicBoolean(false)
        val producer = Thread({
            try {
                for (item in items) {
                    if (stopped.get() || Thread.currentThread().isInterrupted) break
                    val result = produce(item)
                    // Native calls may clear/ignore interrupt. Never block on a full queue after the consumer left.
                    if (stopped.get() || Thread.currentThread().isInterrupted) break
                    queue.put(result)
                }
            } catch (t: Throwable) {
                // An interrupt is our own signal that the consumer has already failed or the user stopped the book.
                if (!stopped.get()) failure.compareAndSet(null, t)
            } finally {
                done.set(true)
            }
        }, "sa3-dit")
        producer.isDaemon = true
        producer.start()
        try {
            while (true) {
                val clip = queue.poll(200, TimeUnit.MILLISECONDS)
                if (clip != null) consume(clip)
                else if (done.get() && queue.isEmpty()) break
            }
        } catch (t: Throwable) {
            stopped.set(true)
            failure.compareAndSet(null, t)
            if (t is InterruptedException) Thread.currentThread().interrupt()
            throw t
        } finally {
            stopped.set(true)
            producer.interrupt()
            // The caller owns the graphs and closes them as soon as we return. A timeout or an interrupted join
            // must not release that ownership while a native call still uses them.
            var interrupted = Thread.interrupted()
            while (producer.isAlive) {
                try { producer.join() } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        failure.get()?.let { throw it }
    }
}
