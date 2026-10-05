package com.brahmadeo.supertonic.tts.tera

/** Vocoder windows over latent frames. The vocoder is causal: with [CONTEXT] left frames
 * every window reproduces a single full pass bit-for-bit (desktop ORT check, 2026-10-05),
 * so window size changes only speed and streaming granularity, never the audio.
 * The first window stays short for quick first audio; later ones are larger because each
 * window recomputes its context (16-frame windows cost ~2.2x one pass, 64-frame ~1.3x). */
internal object TeraVocoderChunks {
    const val CONTEXT = 20
    const val FIRST = 16
    const val NEXT = 64

    data class Window(val contextStart: Int, val start: Int, val end: Int)

    fun plan(frames: Int): List<Window> {
        val windows = ArrayList<Window>()
        var start = 0
        while (start < frames) {
            val end = minOf(start + if (start == 0) FIRST else NEXT, frames)
            windows += Window((start - CONTEXT).coerceAtLeast(0), start, end)
            start = end
        }
        return windows
    }
}
