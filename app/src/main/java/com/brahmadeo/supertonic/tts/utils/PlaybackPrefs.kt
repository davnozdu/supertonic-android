package com.brahmadeo.supertonic.tts.utils

import android.content.Context

/**
 * User-tunable playback behavior.
 *
 * Two independent knobs:
 *
 * - [chunkMode] — controls how aggressively [TextNormalizer.splitIntoSentences]
 *   merges sentences before handing them to the synthesis engine. Smaller
 *   chunks mean the first audio arrives sooner (good for short notifications)
 *   but the engine plans intonation per-chunk, so prosody continuity across
 *   sentence breaks suffers; bigger chunks mean smoother prose at the cost of
 *   longer initial latency. DEFAULT keeps the legacy 300-char limit.
 *
 * - [preRollEnabled] / [preRollSentences] — when on, [PlaybackService] holds
 *   off starting AudioTrack until N sentences worth of PCM are queued in the
 *   in-memory Channel. The producer races ahead while the user waits a few
 *   extra seconds at the start; after that the consumer always has a steady
 *   pool of synthesized audio to dip into, so brief RTF dips (thermal
 *   throttle, GC, background apps) don't underrun the speaker. Costs ~1-3 MB
 *   of RAM during playback — never written to flash.
 *
 * Both default OFF / DEFAULT so existing setups don't change behavior unless
 * the user opts in. Singleton mirroring [PunctuationPrefs] / [LexiconManager]
 * so the synthesis pipeline doesn't need to thread these through every call.
 */
object PlaybackPrefs {
    private const val PREFS_NAME = "PlaybackPrefs"
    private const val KEY_CHUNK_MODE = "chunk_mode"
    private const val KEY_PREROLL_ENABLED = "preroll_enabled"
    private const val KEY_PREROLL_SENTENCES = "preroll_sentences"
    private const val KEY_AUTO_STEPS = "auto_steps"

    enum class ChunkMode(val limit: Int) {
        // Tuned so a typical Russian sentence stays within one chunk:
        //   SMALL covers a short SMS/notification — sub-second first audio.
        //   DEFAULT is the legacy 300-char limit, balanced for most prose.
        //   LARGE merges multiple sentences for narration with continuous
        //   intonation arcs — best for audiobooks.
        //   HUGE (experimental) merges entire short paragraphs into one
        //   synthesis call. Useful for system-TTS callers like Moon+ Reader
        //   that ship single-sentence paragraphs — at HUGE we still pack
        //   them together if they arrive in the same speak() request.
        //   Cost: bigger model activations, longer first-chunk latency.
        SMALL(120),
        DEFAULT(300),
        LARGE(500),
        HUGE(1000);

        companion object {
            fun fromOrdinal(i: Int): ChunkMode = values().getOrNull(i) ?: DEFAULT
        }
    }

    @Volatile var chunkMode: ChunkMode = ChunkMode.DEFAULT
        private set
    @Volatile var preRollEnabled: Boolean = false
        private set
    // Range 1..5. Clamped on every setter so a corrupt prefs file can't push
    // pre-roll into pathological values that would either defeat the feature
    // (0) or eat too much RAM (10+).
    @Volatile var preRollSentences: Int = 2
        private set
    // When ON, services pick diffusion steps based on SupertonicTTS.getSoC()
    // instead of the user's manual SupertonicPrefs.diffusion_steps. The
    // mapping (see resolveSteps) gives weak SoCs 3 steps for ~40% lower
    // first-chunk latency, mid devices 4 steps, top tiers stay at 5.
    // Default OFF so behavior matches manual selection unless opted in.
    @Volatile var autoSteps: Boolean = false
        private set

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        chunkMode = ChunkMode.fromOrdinal(p.getInt(KEY_CHUNK_MODE, ChunkMode.DEFAULT.ordinal))
        preRollEnabled = p.getBoolean(KEY_PREROLL_ENABLED, false)
        preRollSentences = p.getInt(KEY_PREROLL_SENTENCES, 2).coerceIn(1, 5)
        autoSteps = p.getBoolean(KEY_AUTO_STEPS, false)
    }

    /**
     * Decide how many diffusion steps to use for a synthesis call.
     *
     * When [autoSteps] is OFF (default), returns [userSteps] verbatim — the
     * legacy behavior where the slider on the main screen controls everything.
     *
     * When ON, ignores [userSteps] and picks by [SupertonicTTS.getSoC()] class:
     *   0 (LowEnd)   → 3   ~40% faster first chunk vs 5 steps, minor quality dip
     *   1 (MidRange) → 4   balance — barely audible quality difference vs 5
     *   2 (HighEnd)  → 5   no need to go lower, SoC handles it
     *   3 (Flagship) → 5   capping at 5 — going higher (6-7) only hurts latency
     *  -1 (engine not yet initialised) → fall back to userSteps
     */
    fun resolveSteps(socClass: Int, userSteps: Int): Int {
        if (!autoSteps) return userSteps
        return when (socClass) {
            0 -> 3
            1 -> 4
            2, 3 -> 5
            else -> userSteps
        }
    }

    fun setChunkMode(context: Context, mode: ChunkMode) {
        chunkMode = mode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CHUNK_MODE, mode.ordinal).apply()
    }

    fun setPreRollEnabled(context: Context, enabled: Boolean) {
        preRollEnabled = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PREROLL_ENABLED, enabled).apply()
    }

    fun setPreRollSentences(context: Context, count: Int) {
        val clamped = count.coerceIn(1, 5)
        preRollSentences = clamped
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PREROLL_SENTENCES, clamped).apply()
    }

    fun setAutoSteps(context: Context, value: Boolean) {
        autoSteps = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_STEPS, value).apply()
    }
}
