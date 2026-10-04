package com.brahmadeo.supertonic.tts.utils

/** PCM is stored in Java byte arrays; leave space for synthesis and playback allocations. */
object SpeechCacheBudget {
    private const val MB = 1024L*1024L
    fun limit(requestedMb: Int, heapBytes: Long): Long {
        val reserve = (heapBytes/3).coerceIn(64*MB,128*MB)
        return minOf(requestedMb.coerceIn(64,1024)*MB,(heapBytes-reserve).coerceAtLeast(16*MB))
    }
}
