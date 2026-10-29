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
    data class Scene(val key: String, val name: String, val files: List<File>) {
        /** Mood music («Музыка по настроению»): never compressed — only the scene sounds' sudden events are tamed. */
        val music get() = key.startsWith("music/")
    }

    private const val CROSSFADE_MS = 3000L
    private const val SCENE_FADE_MS = 1500L
    private const val TICK_MS = 50L
    private val handler = Handler(Looper.getMainLooper())

    /** Headroom above the music-level clips (+10 dB) and «Сглаживать резкие звуки»: on Android 9+ one
     * DynamicsProcessing per player — a compressor that keeps the events (a whistle, a burst of laughter) near the
     * steady background, its make-up gain, then a limiter; older systems get the platform LoudnessEnhancer (+10 dB). */
    private const val BOOST_DB = 10f
    private const val BOOST_MB = 1000
    @Volatile var tame = true
        set(value) { if (field != value) { field = value; handler.post { voices.forEach { it.applyTame() } } } }

    private class Voice(val player: MediaPlayer, val scene: Scene, val clip: Int,
                        /** One-shots keep their impact: the compressor is for the accents inside the steady bed. */
                        private val tameable: Boolean = !scene.music) {
        private val dynamics: Any? = if (android.os.Build.VERSION.SDK_INT >= 28) Dynamics.create(player.audioSessionId, tameable) else null
        private val boost: android.media.audiofx.LoudnessEnhancer? = if (dynamics != null) null else runCatching {
            android.media.audiofx.LoudnessEnhancer(player.audioSessionId).apply { setTargetGain(BOOST_MB); enabled = true }
        }.getOrNull()
        fun applyTame() { if (android.os.Build.VERSION.SDK_INT >= 28) Dynamics.configure(dynamics, tameable) }
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
        fun create(session: Int, tameable: Boolean): Any? = runCatching {
            val config = android.media.audiofx.DynamicsProcessing.Config.Builder(
                android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, 2, false, 0, true, 1, false, 0, true).build()
            android.media.audiofx.DynamicsProcessing(0, session, config).also { configure(it, tameable); it.enabled = true }
        }.getOrNull()

        /** Clips are at −14 LUFS (steady background ≈ −17 dBFS RMS). Taming: above −14 dBFS 4:1 (attack 5 ms, release
         * 400 ms), so an event 12 dB over the background comes out ~3 dB over it; off: no compression. Then +10 dB
         * and a −1 dBFS limiter (no clipping, no clicks). */
        fun configure(effect: Any?, tameable: Boolean) {
            val d = effect as? android.media.audiofx.DynamicsProcessing ?: return
            runCatching {
                val band = android.media.audiofx.DynamicsProcessing.MbcBand(true, 20000f, 5f, 400f, if (tame && tameable) 4f else 1f, -14f, 6f, -90f, 1f, 0f, BOOST_DB)
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
    /** «Дыхание звука»: [duck] lowers the scene under speech (−3 dB) and lifts it in the pauses; [establish] makes a new
     * scene ~4 dB louder for its first seconds. [chapter] is the dip of a new section (also without breathing). */
    @Volatile var breathe = false
    private var duck = 1f
    private var duckTarget = 1f
    private var establishUntil = 0L
    private var chapterAt = 0L
    private const val DUCK = 0.7f            // −3 dB under speech
    private const val DUCK_MS = 300L
    private const val ESTABLISH = 1.6f       // +4 dB
    private const val ESTABLISH_HOLD_MS = 3000L
    private const val ESTABLISH_FADE_MS = 3000L

    fun speaking(on: Boolean) { duckTarget = if (on && breathe) DUCK else 1f; handler.post { if (playing) schedule() } }

    /** A new section: everything dips out (1.2 s), holds (0.8 s) and comes back (2.5 s). */
    fun chapter() { chapterAt = android.os.SystemClock.uptimeMillis(); handler.post { if (playing) schedule() } }

    /** The section dip, shared with the music (BackgroundMusic multiplies its gain by it). */
    fun chapterGain(now: Long = android.os.SystemClock.uptimeMillis()): Float {
        val t = now - chapterAt
        return when {
            chapterAt == 0L || t >= 4500 -> 1f
            t < 1200 -> 1f - t / 1200f
            t < 2000 -> 0f
            else -> (t - 2000) / 2500f
        }
    }

    private fun establishGain(now: Long): Float {
        if (!breathe || now >= establishUntil + ESTABLISH_FADE_MS) return 1f
        if (now < establishUntil) return ESTABLISH
        return ESTABLISH - (ESTABLISH - 1f) * (now - establishUntil) / ESTABLISH_FADE_MS.toFloat()
    }

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
            establishUntil = android.os.SystemClock.uptimeMillis() + SCENE_FADE_MS + ESTABLISH_HOLD_MS
            start(target, 0, SCENE_FADE_MS)
        }
        masterTarget = 1f
        if (!playing) { playing = true; voices.forEach { runCatching { applyGain(it); it.player.start() } } }
        schedule()
    }

    /** Reading paused or stopped: a quick fade, then everything holds where it is. */
    fun pause() {
        cancelShots()
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
        cancelShots()
        voices.forEach { it.release() }
        voices.clear(); scene = null; playing = false; master = 0f; masterTarget = 0f
    }

    /** A one-shot sound (an event, the chapter transition) over the background after [delayMs], with the headroom and
     * the limiter of the scene sounds but without their compressor: an event is what the listener should notice, not an
     * accent to be levelled into the bed. Its loudness is the events' own slider. Released when it ends. */
    /** Разнообразие: у вида до трёх вариантов клипа (k_door_slam.m4a, _2, _3) — каждый раз берём следующий по кругу,
     * чтобы один и тот же скрип не звучал одинаково. */
    private val turns = HashMap<String, Int>()

    private fun variant(file: java.io.File): java.io.File {
        val name = file.name
        if (!name.startsWith("k_") || !name.endsWith(".m4a")) return file
        val base = name.removeSuffix(".m4a")
        val dir = file.parentFile ?: return file
        val all = listOf(file, java.io.File(dir, "${base}_2.m4a"), java.io.File(dir, "${base}_3.m4a"))
            .filter { it.isFile && it.length() > 0L }
        if (all.size < 2) return file
        val n = turns[base] ?: 0
        turns[base] = (n + 1) % all.size
        return all[n % all.size]
    }

    fun oneShot(ctx: Context, file: java.io.File, delayMs: Long, volume: Float) {
        lateinit var run: Runnable
        run = Runnable {
            pending.remove(run)
            // Reading paused or stopped in between: the moment is gone.
            if (!playing || masterTarget == 0f) return@Runnable
            // An event is still sounding: the next one is skipped, two sounds never overlap (the voice is what matters).
            if (shots.isNotEmpty() || android.os.SystemClock.elapsedRealtime() < shotUntil) {
                AmbienceScenes.trace(ctx, "one-shot skipped: ${file.name} waits for the previous")
                return@Runnable
            }
            val clip = variant(file)
            try {
                val p = MediaPlayer()
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                p.setDataSource(clip.path); p.prepare()
                val v = Voice(p, Scene(clip.name, clip.name, listOf(clip)), 0, tameable = false)
                // Плавное появление: 4 шага по 20 мс от нуля — нет щелчка в начале клипа.
                p.setVolume(0f, 0f)
                shots += v
                // Сколько звучит клип — столько следующий и ждёт (длительность иногда 0 сразу после prepare).
                // В базе клип 8 с — это запас на затухание; звучит столько, сколько выбрано в настройках.
                val length = if (p.duration > 0) p.duration.toLong() else 8_000L
                val want = (AmbienceSettings.eventSeconds(ctx.getSharedPreferences("SupertonicPrefs", 0)) * 1000).toLong()
                val limit = minOf(length, want.coerceAtLeast(600L))
                shotUntil = android.os.SystemClock.elapsedRealtime() + limit
                p.setOnCompletionListener { handler.post {
                    shots.remove(v); v.release()
                    // Клип кончился раньше срока — снимаем запрет, следующий звук уже может играть.
                    if (shots.isEmpty()) shotUntil = 0L
                } }
                p.start()
                val inSteps = 4
                val inStep = 20L
                for (i in 1..inSteps) handler.postDelayed({
                    val k = volume * i / inSteps
                    runCatching { p.setVolume(k, k) }
                }, inStep * i)
                // Длинный клип обрезаем: плавное затухание 400 мс и стоп, чтобы шорох не тянулся за пределы выбранной длины.
                if (limit < length) {
                    val steps = 10
                    val step = 40L
                    for (i in 1..steps) handler.postDelayed({
                        val k = volume * (1f - i / steps.toFloat())
                        runCatching { p.setVolume(k, k) }
                    }, (limit - steps * step).coerceAtLeast(0L) + step * i)
                    handler.postDelayed({
                        runCatching { p.setOnCompletionListener(null) }   // отписка: ниже освобождаем сами
                        runCatching { if (p.isPlaying) p.stop() }
                        runCatching { v.release() }
                        shots.remove(v)
                        if (shots.isEmpty()) shotUntil = 0L
                    }, limit + step)
                }
            } catch (e: Exception) { AmbienceScenes.trace(ctx, "one-shot failed ${e.javaClass.simpleName}") }
        }
        pending += run
        // Абзац у читалки бывает и на минуту: событие в его конце должно ждать свои секунды, а не сваливаться
        // в общую кучу на 20-й секунде (тогда играет одно, остальные отбрасываются как наложение).
        handler.postDelayed(run, delayMs.coerceIn(0, 180_000))
    }
    private val shots = ArrayList<Voice>()
    /** Когда закончится звучащий сейчас звук события: следующий ранее этого не начинается. */
    private var shotUntil = 0L
    private val pending = ArrayList<Runnable>()


    /** Pause, stop: the events and chapter sounds still waiting are dropped and the sounding ones stop. Main thread. */
    private fun cancelShots() {
        turns.clear()
        pending.forEach { handler.removeCallbacks(it) }; pending.clear()
        shots.forEach { runCatching { it.player.setVolume(0f, 0f) }; it.release() }; shots.clear()
        shotUntil = 0L
    }

    /** Any other variant at random: the events come at no fixed interval and the loop never repeats the same way. */
    private fun nextClip(scene: Scene, current: Int): Int {
        val n = scene.files.size
        return if (n <= 1) current else (current + 1 + kotlin.random.Random.nextInt(n - 1)) % n
    }

    /** Equal power (sqrt): a crossfade of two sounds keeps the loudness. */
    private fun applyGain(v: Voice) {
        val now = android.os.SystemClock.uptimeMillis()
        val g = (kotlin.math.sqrt(v.level) * master * volume * duck * establishGain(now) * chapterGain(now)).coerceAtMost(1f)
        runCatching { v.player.setVolume(g, g) }
    }

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
        val dStep = TICK_MS.toFloat() / DUCK_MS * (1f - DUCK)
        duck = if (duck < duckTarget) minOf(duckTarget, duck + dStep) else maxOf(duckTarget, duck - dStep)
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
