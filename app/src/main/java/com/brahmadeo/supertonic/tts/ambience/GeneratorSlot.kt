package com.brahmadeo.supertonic.tts.ambience

/**
 * One heavy generator per worker process. The book sounds/music service and the sound-bank generator now share
 * ":soundgen", and each takes gigabytes: two at once do not fit in the phone's memory (measured: 4.8 GB of dmabuf for
 * the medium model alone). Whoever comes second is refused and says so, instead of both being killed.
 */
object GeneratorSlot {
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True when this caller now owns the generator; it must call [release] in its finally block. */
    fun tryAcquire(): Boolean = busy.compareAndSet(false, true)
    fun release() { busy.set(false) }
    val taken: Boolean get() = busy.get()
}
