package com.brahmadeo.supertonic.tts.llm

/** How a reader app hands text over. Moon+ adds utterances behind the current one (QUEUE_ADD), so MyTTS prepares them
 * ahead; Readest sends one sentence at a time with QUEUE_FLUSH after the previous one ends, so every sentence would
 * wait for the cloud LLM (~0.5-1.5 s of silence). Keyed by the app's UID: Readest reconnects after idle. */
internal class ReaderQueueStyle {
    private class Seen(var flushes: Int = 0, var adds: Int = 0)
    private val apps = LinkedHashMap<Int, Seen>()

    @Synchronized fun record(uid: Int, flush: Boolean) {
        val seen = apps.getOrPut(uid) { Seen() }
        if (flush) seen.flushes++ else seen.adds++
        while (apps.size > 16) apps.remove(apps.keys.first())
    }

    /** Two utterances or more seen from [uid], none queued behind another. */
    @Synchronized fun oneAtATime(uid: Int): Boolean = apps[uid]?.let { it.adds == 0 && it.flushes >= 2 } ?: false
}
