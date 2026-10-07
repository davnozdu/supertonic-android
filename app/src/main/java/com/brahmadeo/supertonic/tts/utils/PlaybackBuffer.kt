package com.brahmadeo.supertonic.tts.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The bounded PCM channel between the synthesis thread and the AudioTrack writer. */
object PlaybackBuffer {
    const val CAPACITY = 500

    /** Send from the synchronous synthesis callback; false once the channel is closed or cancelled.
     * The wait is not tied to any coroutine Job: only channel.cancel() wakes a sender parked on a
     * full channel (close() does not), so stopping must cancel the channel before joining the job. */
    fun <T> sendBlocking(channel: SendChannel<T>, item: T): Boolean = try {
        runBlocking { channel.send(item) }; true
    } catch (_: ClosedSendChannelException) { false
    } catch (_: CancellationException) { false
    } catch (_: InterruptedException) { false }
}

/** Holds playback until [sentences] are synthesized, or earlier once the queued audio reaches
 * [maxPackets] or [maxBytes]: the consumer reads nothing before the gate opens, so a full channel
 * would block the producer before it could finish the sentence that opens it. */
class PreRollGate(private val sentences: Int, private val maxPackets: Int, private val maxBytes: Long) {
    private val signal = CompletableDeferred<Unit>()
    private val packets = AtomicInteger()
    private val bytes = AtomicLong()
    val isOpen get() = signal.isCompleted
    suspend fun await() = signal.await()
    fun open() { signal.complete(Unit) }
    /** Call before every send into the channel. */
    fun queued(size: Int) {
        if (signal.isCompleted) return
        val count = packets.incrementAndGet(); val total = bytes.addAndGet(size.toLong())
        if (count >= maxPackets || total >= maxBytes) open()
    }
    fun sentenceDone(produced: Int) { if (produced >= sentences) open() }

    companion object {
        /** Open well before the channel fills, and after at most a minute of audio. */
        fun forChannel(sentences: Int, sampleRate: Int) =
            PreRollGate(sentences, PlaybackBuffer.CAPACITY * 3 / 4, sampleRate.coerceAtLeast(8000) * 2L * 60)
    }
}
