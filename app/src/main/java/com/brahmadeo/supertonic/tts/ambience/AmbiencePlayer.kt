package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File

/** The location sound of the current scene: its clips (2–3 variants of 30 s, equally loud) one after another with
 * [CROSSFADE_MS] crossfades, so the loop never restarts the same way; a new location (or the music) takes over with
 * a [SCENE_FADE_MS] crossfade. Main thread only (driven by BackgroundMusic, which keeps it in step with reading). */
internal object AmbiencePlayer {
    data class Scene(val key: String, val name: String, val files: List<File>)

    private const val CROSSFADE_MS = 3000L
    private const val SCENE_FADE_MS = 2500L
    private const val TICK_MS = 50L
    private val handler = Handler(Looper.getMainLooper())

    /** Headroom above the music-level clips (+10 dB) and «Сглаживать резкие звуки»: on Android 9+ one
     * DynamicsProcessing per player — a compressor that keeps the events (a whistle, a burst of laughter) near the
     * steady background, its make-up gain, then a limiter; older systems get the platform LoudnessEnhancer (+10 dB). */
    private const val BOOST_DB = 10f
    private const val BOOST_MB = 1000
    @Volatile var tame = true
        set(value) { if (field != value) { field = value; handler.post { voices.forEach { it.applyTame() } } } }

    private class Voice(val player: MediaPlayer, val scene: Scene, val clip: Int) {
        private val dynamics: Any? = if (android.os.Build.VERSION.SDK_INT >= 28) Dynamics.create(player.audioSessionId) else null
        private val boost: android.media.audiofx.LoudnessEnhancer? = if (dynamics != null) null else runCatching {
            android.media.audiofx.LoudnessEnhancer(player.audioSessionId).apply { setTargetGain(BOOST_MB); enabled = true }
        }.getOrNull()
        fun applyTame() { if (android.os.Build.VERSION.SDK_INT >= 28) Dynamics.configure(dynamics) }
        fun release() {
            runCatching { (dynamics as? android.media.audiofx.AudioEffect)?.release() }
            runCatching { boost?.release() }; runCatching { player.release() }
        }
        var level = 0f            // 0..1 of the scene volume
        var target = 1f
        var fadeMs = CROSSFADE_MS
        var next = false          // the following clip was already started
    }

    @androidx.annotation.RequiresApi(28)
    private object Dynamics {
        /** The player's DynamicsProcessing, or null when the system has none for it (then: LoudnessEnhancer). */
        fun create(session: Int): Any? = runCatching {
            val config = android.media.audiofx.DynamicsProcessing.Config.Builder(
                android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, 2, false, 0, true, 1, false, 0, true).build()
            android.media.audiofx.DynamicsProcessing(0, session, config).also { configure(it); it.enabled = true }
        }.getOrNull()

        /** Clips are at −14 LUFS (steady background ≈ −17 dBFS RMS). Taming: above −14 dBFS 4:1 (attack 5 ms, release
         * 400 ms), so an event 12 dB over the background comes out ~3 dB over it; off: no compression. Then +10 dB
         * and a −1 dBFS limiter (no clipping, no clicks). */
        fun configure(effect: Any?) {
            val d = effect as? android.media.audiofx.DynamicsProcessing ?: return
            runCatching {
                val band = android.media.audiofx.DynamicsProcessing.MbcBand(true, 20000f, 5f, 400f, if (tame) 4f else 1f, -14f, 6f, -90f, 1f, 0f, BOOST_DB)
                val limiter = android.media.audiofx.DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f)
                for (ch in 0..1) { d.setMbcBandByChannelIndex(ch, 0, band); d.setLimiterByChannelIndex(ch, limiter) }
            }
        }
    }

    private val voices = ArrayList<Voice>()
    private var scene: Scene? = null
    private var volume = 0.3f
    private var playing = false
    private var context: Context? = null
    /** Pause and resume fade every voice together over [PAUSE_FADE_MS] (no click either way). */
    private var master = 0f
    private var masterTarget = 0f
    private const val PAUSE_FADE_MS = 400L

    val active get() = scene != null

    fun play(ctx: Context, target: Scene, volume: Float) {
        context = ctx.applicationContext
        this.volume = volume
        if (scene?.key != target.key) {
            Log.i("Ambience", "Scene ${target.key} «${target.name}» (${target.files.size} clips)")
            AmbienceScenes.trace(ctx, "play ${target.key} clips=${target.files.size} volume=${"%.2f".format(volume)}")
            for (v in voices) { v.target = 0f; v.fadeMs = SCENE_FADE_MS }
            scene = target
            start(target, 0, SCENE_FADE_MS)
        }
        masterTarget = 1f
        if (!playing) { playing = true; voices.forEach { runCatching { applyGain(it); it.player.start() } } }
        schedule()
    }

    /** Reading paused or stopped: a quick fade, then everything holds where it is. */
    fun pause() {
        if (!playing) return
        masterTarget = 0f
        schedule()
    }

    /** Back to the music (no scene here): fade out, then release. */
    fun fadeOut() {
        if (scene == null && voices.isEmpty()) return
        scene = null
        for (v in voices) { v.target = 0f; v.fadeMs = SCENE_FADE_MS }
        if (playing) schedule() else release()
    }

    fun release() {
        handler.removeCallbacks(tick)
        voices.forEach { it.release() }
        voices.clear(); scene = null; playing = false; master = 0f; masterTarget = 0f
    }

    /** Any other variant at random: the events come at no fixed interval and the loop never repeats the same way. */
    private fun nextClip(scene: Scene, current: Int): Int {
        val n = scene.files.size
        return if (n <= 1) current else (current + 1 + kotlin.random.Random.nextInt(n - 1)) % n
    }

    /** Equal power (sqrt): a crossfade of two sounds keeps the loudness. */
    private fun applyGain(v: Voice) { val g = kotlin.math.sqrt(v.level) * master * volume; runCatching { v.player.setVolume(g, g) } }

    private fun start(target: Scene, clip: Int, fadeMs: Long) {
        val ctx = context ?: return
        val file = target.files.getOrNull(clip % target.files.size) ?: return
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
            p.setDataSource(file.path)
            p.prepare()
            p.setVolume(0f, 0f) // fades in from silence: no click
            val v = Voice(p, target, clip % target.files.size).apply { this.fadeMs = fadeMs }
            p.setOnCompletionListener { handler.post { voices.remove(v); v.release() } }
            voices += v
            if (playing) p.start()
        } catch (e: Exception) {
            Log.w("Ambience", "Sound unavailable: ${e.javaClass.simpleName}")
            AmbienceScenes.trace(ctx, "player failed ${e.javaClass.simpleName} on ${file.name}")
        }
    }

    private fun schedule() { handler.removeCallbacks(tick); if (playing) handler.postDelayed(tick, TICK_MS) }

    private val tick: Runnable = Runnable {
        val mStep = TICK_MS.toFloat() / PAUSE_FADE_MS
        master = if (master < masterTarget) minOf(masterTarget, master + mStep) else maxOf(masterTarget, master - mStep)
        for (v in voices.toList()) {
            val step = TICK_MS.toFloat() / v.fadeMs
            // While pausing the crossfades hold: they continue after the resume.
            if (masterTarget > 0f) v.level = if (v.level < v.target) minOf(v.target, v.level + step) else maxOf(v.target, v.level - step)
            applyGain(v)
            if (v.target == 0f && v.level == 0f) { voices.remove(v); v.release(); continue }
            // Before the clip ends the next variant fades in over it.
            val current = scene
            if (masterTarget > 0f && !v.next && v.target > 0f && current != null && v.scene.key == current.key) {
                val left = runCatching { v.player.duration - v.player.currentPosition }.getOrDefault(Int.MAX_VALUE)
                if (left in 1..CROSSFADE_MS) { v.next = true; v.target = 0f; v.fadeMs = CROSSFADE_MS; start(current, nextClip(current, v.clip), CROSSFADE_MS) }
            }
        }
        if (masterTarget == 0f && master == 0f) {
            // Faded out for a pause: hold the players where they are.
            playing = false
            voices.forEach { runCatching { if (it.player.isPlaying) it.player.pause() } }
        } else if (voices.isNotEmpty()) schedule()
    }
}
