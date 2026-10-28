package com.brahmadeo.supertonic.tts.music

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Separate decoder; no audio-focus request or media session to steal reader controls. In a scene of a prepared book
 * with location sounds («Фоновые звуки локаций») the location sound plays instead of the music; outside scenes the
 * music. The two never sound together: the switch is a crossfade (AmbiencePlayer) and the music pauses meanwhile. */
object BackgroundMusic {
    const val DEFAULT_VOLUME = 10
    private val reading = MusicReadingState()
    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var prefs: SharedPreferences? = null
    private var player: MediaPlayer? = null
    private var loadedSource = ""
    private var prepared = false
    private var seeking = false
    private var savedPosition = 0
    private var failedTrack = ""
    private var previewVolume: Int? = null
    private val message = MutableStateFlow("")
    val status = message.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith("background_music_") || key.startsWith("ambience_")) handler.post {
            previewVolume = null
            failedTrack = ""
            update()
        }
    }
    private val idleRelease = Runnable {
        if (!reading.playing() || !enabled()) release(rememberPosition = true)
        if (!reading.playing()) com.brahmadeo.supertonic.tts.ambience.AmbiencePlayer.release()
    }
    /** Scene of each queued utterance (by owner and id), applied when it starts to sound; the current one. */
    private val scenes = java.util.concurrent.ConcurrentHashMap<Pair<Any, String?>, com.brahmadeo.supertonic.tts.ambience.AmbienceScenes.Tag>()
    @Volatile private var scene: com.brahmadeo.supertonic.tts.ambience.AmbiencePlayer.Scene? = null
    /** «Музыка по настроению» of the sounding utterance: played instead of the track where there is no scene. */
    @Volatile private var mood: com.brahmadeo.supertonic.tts.ambience.AmbiencePlayer.Scene? = null
    /** A reader that hands over one utterance at a time (Readest: QUEUE_FLUSH per sentence) leaves the queue empty
     * between sentences: the music plays through such a gap. An explicit stop, pause, timer or call stops it at once. */
    private const val GAP_MS = 4000L
    @Volatile private var gapUntil = 0L
    private val gapEnd = Runnable { update() }
    /** A reader that synthesizes to files and plays them itself (KOReader's audiobook plugin) never says when its
     * audio plays or stops: each request holds the music for about as long as its speech, plus the gap. */
    @Volatile private var holdUntil = 0L
    private const val HOLD_AFTER_STOP_MS = 800L
    private val holdEnd = Runnable { update() }

    @Synchronized fun initialize(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext
        prefs = context!!.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
            .also { it.registerOnSharedPreferenceChangeListener(listener) }
    }
    fun enqueue(ctx: Context, owner: Any, id: String?, flush: Boolean, text: String? = null): Long {
        initialize(ctx)
        if (flush) scenes.keys.removeAll { it.first === owner }
        scenes[owner to id] = com.brahmadeo.supertonic.tts.ambience.AmbienceScenes.tag(ctx, owner, text, flush)
        return reading.enqueue(owner, id, flush).also { refresh() }
    }
    fun started(owner: Any, id: String?) {
        com.brahmadeo.supertonic.tts.ambience.AmbiencePlayer.speaking(true)
        speaking = true; handler.post(shape)
        scenes.remove(owner to id)?.let { found ->
            val next = found.scene
            handler.post { playMoments(found) }
            if (next?.key != scene?.key) context?.let { com.brahmadeo.supertonic.tts.ambience.AmbienceScenes.trace(it, "sounding scene=${next?.key ?: "music"}") }
            scene = next; mood = found.mood
        } ?: context?.let { com.brahmadeo.supertonic.tts.ambience.AmbienceScenes.trace(it, "started without a tagged utterance") }
        reading.start(owner,id); refresh()
    }
    fun finished(owner: Any, id: String?, wasStarted: Boolean? = null) {
        com.brahmadeo.supertonic.tts.ambience.AmbiencePlayer.speaking(false)
        speaking = false; handler.post(shape)
        val was = reading.playing()
        reading.finish(owner,id,wasStarted)
        if (was && !reading.playing()) handler.post {
            gapUntil = android.os.SystemClock.uptimeMillis() + GAP_MS
            handler.removeCallbacks(gapEnd); handler.postDelayed(gapEnd, GAP_MS)
        }
        refresh()
    }
    fun rejected(owner: Any, token: Long) { reading.reject(owner,token); refresh() }
    fun hold(ctx: Context, ms: Long, owner: Any? = null, text: String? = null) {
        initialize(ctx)
        if (owner != null) {
            val tag = com.brahmadeo.supertonic.tts.ambience.AmbienceScenes.tag(ctx, owner, text)
            scene = tag.scene; mood = tag.mood
            handler.post { playMoments(tag) }
        }
        handler.post {
            holdUntil = maxOf(holdUntil, android.os.SystemClock.uptimeMillis() + ms)
            handler.removeCallbacks(holdEnd); handler.postAtTime(holdEnd, holdUntil)
            update()
        }
    }
    /** KOReader's plugin also calls stop() before every sentence it synthesizes: a held track keeps 1.5 s for the
     * next request (0.8 s), so a real pause still silences it. */
    fun stop(owner: Any) {
        reading.stop(owner)
        scenes.keys.removeAll { it.first === owner } // utterances that will never sound
        handler.post {
            gapUntil = 0L; handler.removeCallbacks(gapEnd)
            val now = android.os.SystemClock.uptimeMillis()
            if (holdUntil > now + HOLD_AFTER_STOP_MS) { holdUntil = now + HOLD_AFTER_STOP_MS; handler.removeCallbacks(holdEnd); handler.postAtTime(holdEnd, holdUntil) }
        }
        refresh()
    }
    fun stopTts() { reading.stopTts(); endGap(); refresh() }
    fun app(ctx: Context, playing: Boolean) { initialize(ctx); reading.app(playing); if (!playing) endGap(); refresh() }
    private fun endGap() { handler.post { gapUntil = 0L; holdUntil = 0L; handler.removeCallbacks(gapEnd); handler.removeCallbacks(holdEnd) } }
    fun volumePreview(percent: Int) { handler.post { previewVolume = percent.coerceIn(0,100); updateVolume() } }
    internal data class Snapshot(val reading: Boolean,val prepared: Boolean,val playing: Boolean,val looping: Boolean,val position: Int)
    internal suspend fun snapshot(): Snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        Snapshot(reading.playing(),prepared,runCatching { prepared && player?.isPlaying==true }.getOrDefault(false),
            runCatching { player?.isLooping==true }.getOrDefault(false),runCatching { if(prepared) player?.currentPosition ?: 0 else 0 }.getOrDefault(0))
    }
    private fun refresh() { handler.post {
        update()
    } }
    private fun enabled() = prefs?.getBoolean("background_music_enabled",false) == true
    private fun ambienceVolume() = (prefs?.let { com.brahmadeo.supertonic.tts.ambience.AmbienceSettings.volume(it) }
        ?: com.brahmadeo.supertonic.tts.ambience.AmbienceSettings.DEFAULT_VOLUME) / 100f
    private fun volume() = (previewVolume ?: prefs?.getInt("background_music_volume",DEFAULT_VOLUME) ?: DEFAULT_VOLUME).coerceIn(0,100) / 100f
    private fun updateVolume() { if (prepared) runCatching {
        val breathe = prefs?.let { com.brahmadeo.supertonic.tts.ambience.AmbienceSettings.breathe(it) } == true
        val g = kotlin.math.sqrt(level) * volume() * (if (breathe) duck else 1f) * com.brahmadeo.supertonic.tts.ambience.AmbiencePlayer.chapterGain()
        player?.setVolume(g, g)
    } }

    /** The moments an utterance brings when it starts to sound: the chapter dip with its transition sound (at the quiet
     * point) and an event sound when the voice reaches its words (~180 words a minute). */
    private fun playMoments(tag: com.brahmadeo.supertonic.tts.ambience.AmbienceScenes.Tag) {
        val ctx = context ?: return
        val p = prefs ?: return
        val S = com.brahmadeo.supertonic.tts.ambience.AmbienceSettings
        val player = com.brahmadeo.supertonic.tts.ambience.AmbiencePlayer
        if (tag.chapter && S.chapters(p)) {
            player.chapter(); handler.post(shape)
            tag.chapterSound?.let { player.oneShot(ctx, it, 1200, ambienceVolume()) }
        }
        if (S.events(p)) tag.event?.let { player.oneShot(ctx, it, tag.eventWord * 330L, S.eventsVolume(p) / 100f) }
    }

    /** «Дыхание звука» for the music too (−3 dB under speech, 0.3 s), and the section dip; ticks only while they move. */
    @Volatile private var speaking = false
    private var duck = 1f
    private val shape: Runnable = object : Runnable { override fun run() {
        val target = if (speaking) 0.7f else 1f
        duck = if (duck < target) minOf(target, duck + 0.05f) else maxOf(target, duck - 0.05f)
        updateVolume()
        if (duck != target || com.brahmadeo.supertonic.tts.ambience.AmbiencePlayer.chapterGain() < 1f) { handler.removeCallbacks(this); handler.postDelayed(this, 50) }
    } }

    /** The music fades instead of cutting: out under a scene's sound and back after it (as long as the scene
     * crossfade), quickly on pause or resume; equal-power gain (sqrt), so a crossfade keeps the loudness. */
    private var level = 0f
    private var target = 0f
    private var fadeMs = QUICK_FADE_MS
    private const val SCENE_FADE_MS = 2500L
    private const val QUICK_FADE_MS = 400L
    private const val FADE_TICK_MS = 50L
    private val fade: Runnable = object : Runnable { override fun run() {
        val step = FADE_TICK_MS.toFloat() / fadeMs
        level = if (level < target) minOf(target, level + step) else maxOf(target, level - step)
        updateVolume()
        if (level == 0f && target == 0f) { if (prepared) runCatching { if (player?.isPlaying == true) player?.pause() }; return }
        if (level != target) handler.postDelayed(this, FADE_TICK_MS)
    } }
    private fun fadeTo(value: Float, ms: Long) {
        target = value; fadeMs = ms
        handler.removeCallbacks(fade)
        if (level != target) handler.post(fade) else if (target == 0f) fade.run()
    }
    private var underScene = false
    private var lastDecision = ""

    private fun update() {
        val ctx = context ?: return
        // A scene with location sounds: they play, the music waits (keeps its place in the track).
        val now = android.os.SystemClock.uptimeMillis()
        val active = reading.playing() || now < holdUntil
        val located = scene?.takeIf { prefs?.let { com.brahmadeo.supertonic.tts.ambience.AmbienceSettings.enabled(it) } == true }
        // Outside scenes, with the music on: the book's music of this mood instead of the track. Its clips are at
        // −14 LUFS with the +10 dB headroom of the player, so it plays 10 dB under the music slider to match the tracks.
        val moody = if (located == null && enabled()) mood?.takeIf { prefs?.let { com.brahmadeo.supertonic.tts.ambience.AmbienceSettings.moodMusic(it) } == true } else null
        val current = located ?: moody
        // What sounds now and why (verbose trace only; logged when it changes): finds a music that stops mid-reading.
        val decision = "decide ${current?.key ?: "track"} reading=${reading.playing()} hold=${now < holdUntil} gap=${now < gapUntil} enabled=${enabled()}"
        if (decision != lastDecision) { lastDecision = decision; com.brahmadeo.supertonic.tts.ambience.AmbienceScenes.trace(ctx, decision) }
        if (current != null) {
            if (prepared && player?.isPlaying == true) fadeTo(0f, SCENE_FADE_MS)
            underScene = true
            if (active || now < gapUntil) {
                handler.removeCallbacks(idleRelease)
                prefs?.let {
                    com.brahmadeo.supertonic.tts.ambience.AmbiencePlayer.tame = com.brahmadeo.supertonic.tts.ambience.AmbienceSettings.tame(it)
                    com.brahmadeo.supertonic.tts.ambience.AmbiencePlayer.breathe = com.brahmadeo.supertonic.tts.ambience.AmbienceSettings.breathe(it)
                }
                com.brahmadeo.supertonic.tts.ambience.AmbiencePlayer.play(ctx, current, if (current === moody) volume() / 3.16f else ambienceVolume())
            } else {
                com.brahmadeo.supertonic.tts.ambience.AmbiencePlayer.pause()
                handler.removeCallbacks(idleRelease); handler.postDelayed(idleRelease, 120_000)
            }
            return
        }
        com.brahmadeo.supertonic.tts.ambience.AmbiencePlayer.fadeOut()
        val track = prefs?.getString("background_music_track", "").orEmpty().ifEmpty { MusicCatalog.ensureDefault(ctx) }
        val source = if(track=="custom") "custom:${prefs?.getString("background_music_file","")}" else track
        if (source != loadedSource && loadedSource.isNotEmpty()) {
            release(false)
            savedPosition = 0
            loadedSource = ""
        }
        val held = android.os.SystemClock.uptimeMillis() < holdUntil
        if (!enabled() || !(reading.playing() || held) || track.isEmpty()) {
            // Between two utterances of the same reading: keep playing until the gap ends.
            if (enabled() && track.isNotEmpty() && android.os.SystemClock.uptimeMillis() < gapUntil) return
            if (prepared && player?.isPlaying == true) fadeTo(0f, QUICK_FADE_MS)
            handler.removeCallbacks(idleRelease)
            handler.postDelayed(idleRelease,120_000)
            return
        }
        handler.removeCallbacks(idleRelease)
        if (failedTrack == track) return
        if (player != null) {
            // Every utterance refreshes the state (KOReader: several times a sentence); a playing track is left alone.
            if (prepared && !seeking) runCatching {
                if (player?.isPlaying != true) { level = 0f; updateVolume(); player?.start() }
                if (target != 1f || level < 1f) fadeTo(1f, if (underScene) SCENE_FADE_MS else QUICK_FADE_MS)
                underScene = false
            }.onFailure { fail(track) }
            return
        }
        loadedSource = source
        try {
            val current = MediaPlayer()
            player = current
            current.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            current.setWakeMode(ctx,PowerManager.PARTIAL_WAKE_LOCK)
            current.isLooping = true
            current.setOnErrorListener { _, _, _ -> if (player === current) fail(track); true }
            current.setOnSeekCompleteListener { if(player===current) { seeking=false;update() } }
            current.setOnPreparedListener {
                if (player === current) {
                    runCatching {
                        prepared = true
                        if (savedPosition > 0 && current.duration > 0) {
                            seeking=true
                            current.seekTo(savedPosition % current.duration)
                        }
                        message.value = ""
                        update()
                        Log.i("BackgroundMusic","Prepared looping track; volume=${(volume()*100).toInt()}%")
                    }.onFailure { fail(track) }
                }
            }
            val file = if(track.startsWith("ready:")) MusicCatalog.selected(ctx,track.removePrefix("ready:"))
                else if(track=="custom") MusicFiles.custom(ctx)
                else if(track.startsWith("gen:")) GeneratedMusic.file(ctx,track.removePrefix("gen:")) else null
            current.setDataSource(requireNotNull(file).absolutePath)
            current.prepareAsync()
        } catch (_: Exception) { fail(track) }
    }
    private fun fail(track: String) {
        failedTrack = track
        release(false)
        message.value = "Не удалось воспроизвести музыку. Выберите другой MP3. Чтение продолжается."
        Log.w("BackgroundMusic","Music unavailable; speech is unaffected")
    }
    private fun release(rememberPosition: Boolean) {
        if (rememberPosition && prepared) savedPosition = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
        handler.removeCallbacks(fade); level = 0f; target = 0f
        player?.let { runCatching { it.release() } }
        player = null
        prepared = false
        seeking = false
    }
}
