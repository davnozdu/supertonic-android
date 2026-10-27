package com.brahmadeo.supertonic.tts.llm

import android.os.SystemClock

/** APInex gateway (OpenAI-compatible, the same request as [DeepSeekApi]); credentials only from encrypted settings.
 * It limits requests per minute per key (free plan: 15 by `/v1/balance` and the x-ratelimit headers; the model page
 * says 5). The limit is read from `/v1/balance` once an hour and requests are spaced here, so the gateway never answers
 * 429 (which would pause the provider for a minute). A request that would wait past its deadline fails at once. */
object ApinexApi {
    const val ENDPOINT = "https://api.apinex.bond/v1"
    const val DEFAULT_MODEL = "free/deepseek-v4.1-flash"
    private const val FALLBACK_PER_MINUTE = 15
    private val sent = ArrayDeque<Long>()
    @Volatile var perMinute = FALLBACK_PER_MINUTE; private set
    @Volatile private var checkedAt = 0L

    fun free(model: String) = model.startsWith("free/")

    /** The account's limit from `/v1/balance` (its «rpm»), at most once an hour; the last known one if it fails. */
    fun refresh(balance: () -> org.json.JSONObject) {
        val now = SystemClock.elapsedRealtime()
        if (checkedAt != 0L && now - checkedAt < 3_600_000L) return
        checkedAt = now
        runCatching { balance().optInt("rpm", 0) }.getOrNull()?.takeIf { it in 1..1000 }?.let { perMinute = it }
    }

    /** Waits for a free slot of the minute, at most [maxWaitMs]. */
    fun acquire(maxWaitMs: Long, cancelled: () -> Unit = {}) {
        val until = SystemClock.elapsedRealtime() + maxWaitMs
        while (true) {
            cancelled()
            val wait = synchronized(sent) {
                val now = SystemClock.elapsedRealtime()
                // 61 s: a margin for the gateway's own clock.
                while (sent.isNotEmpty() && now - sent.first() >= 61_000) sent.removeFirst()
                if (sent.size < perMinute) { sent.addLast(now); return }
                sent.first() + 61_000 - now
            }
            if (SystemClock.elapsedRealtime() + wait > until) throw java.io.IOException("APInex: лимит $perMinute запросов в минуту")
            Thread.sleep(minOf(wait, 500L))
        }
    }
}
