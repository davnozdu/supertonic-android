package com.brahmadeo.supertonic.tts.llm

import android.os.SystemClock

/** APInex gateway (OpenAI-compatible, the same request as [DeepSeekApi]); credentials only from encrypted settings.
 * Its free models («free/…») allow 5 requests a minute per IP: requests are spaced here so the gateway never answers
 * 429 (which would pause the provider for a minute). A request that would wait past its deadline fails at once. */
object ApinexApi {
    const val ENDPOINT = "https://api.apinex.bond/v1"
    const val DEFAULT_MODEL = "free/deepseek-v4.1-flash"
    private const val FREE_PER_MINUTE = 5
    private val sent = ArrayDeque<Long>()

    fun free(model: String) = model.startsWith("free/")

    /** Waits for a free slot of the free tier, at most [maxWaitMs]. */
    fun acquire(model: String, maxWaitMs: Long, cancelled: () -> Unit = {}) {
        if (!free(model)) return
        val until = SystemClock.elapsedRealtime() + maxWaitMs
        while (true) {
            cancelled()
            val wait = synchronized(sent) {
                val now = SystemClock.elapsedRealtime()
                // 61 s: a margin for the gateway's own clock.
                while (sent.isNotEmpty() && now - sent.first() >= 61_000) sent.removeFirst()
                if (sent.size < FREE_PER_MINUTE) { sent.addLast(now); return }
                sent.first() + 61_000 - now
            }
            if (SystemClock.elapsedRealtime() + wait > until) throw java.io.IOException("APInex: лимит бесплатного режима — 5 запросов в минуту")
            Thread.sleep(minOf(wait, 500L))
        }
    }
}
