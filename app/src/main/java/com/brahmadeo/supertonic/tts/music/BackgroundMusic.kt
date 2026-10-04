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

/** Separate decoder; no audio-focus request or media session to steal reader controls. */
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
        if (key == null || key.startsWith("background_music_")) handler.post {
            previewVolume = null
            failedTrack = ""
            update()
        }
    }
    private val idleRelease = Runnable { if (!reading.playing() || !enabled()) release(rememberPosition = true) }

    @Synchronized fun initialize(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext
        prefs = context!!.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
            .also { it.registerOnSharedPreferenceChangeListener(listener) }
    }
    fun enqueue(ctx: Context, owner: Any, id: String?, flush: Boolean): Long {
        initialize(ctx)
        return reading.enqueue(owner, id, flush).also { refresh() }
    }
    fun started(owner: Any, id: String?) { reading.start(owner,id); refresh() }
    fun finished(owner: Any, id: String?, wasStarted: Boolean? = null) { reading.finish(owner,id,wasStarted); refresh() }
    fun rejected(owner: Any, token: Long) { reading.reject(owner,token); refresh() }
    fun stop(owner: Any) { reading.stop(owner); refresh() }
    fun stopTts() { reading.stopTts(); refresh() }
    fun app(ctx: Context, playing: Boolean) { initialize(ctx); reading.app(playing); refresh() }
    fun volumePreview(percent: Int) { handler.post { previewVolume = percent.coerceIn(0,100); updateVolume() } }
    internal data class Snapshot(val reading: Boolean,val prepared: Boolean,val playing: Boolean,val looping: Boolean,val position: Int)
    internal suspend fun snapshot(): Snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        Snapshot(reading.playing(),prepared,runCatching { prepared && player?.isPlaying==true }.getOrDefault(false),
            runCatching { player?.isLooping==true }.getOrDefault(false),runCatching { if(prepared) player?.currentPosition ?: 0 else 0 }.getOrDefault(0))
    }
    private fun refresh() { handler.post { update() } }
    private fun enabled() = prefs?.getBoolean("background_music_enabled",false) == true
    private fun volume() = (previewVolume ?: prefs?.getInt("background_music_volume",DEFAULT_VOLUME) ?: DEFAULT_VOLUME).coerceIn(0,100) / 100f
    private fun updateVolume() { if (prepared) runCatching { player?.setVolume(volume(),volume()) } }

    private fun update() {
        val ctx = context ?: return
        val track = prefs?.getString("background_music_track", "").orEmpty()
        val source = if(track=="custom") "custom:${prefs?.getString("background_music_file","")}" else track
        if (source != loadedSource && loadedSource.isNotEmpty()) {
            release(false)
            savedPosition = 0
            loadedSource = ""
        }
        if (!enabled() || !reading.playing() || track.isEmpty()) {
            if (prepared) runCatching { if(player?.isPlaying==true) player?.pause() }
            handler.removeCallbacks(idleRelease)
            handler.postDelayed(idleRelease,120_000)
            return
        }
        handler.removeCallbacks(idleRelease)
        if (failedTrack == track) return
        if (player != null) {
            if (prepared && !seeking) runCatching { updateVolume(); player?.start() }.onFailure { fail(track) }
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
                else if(track=="custom") MusicFiles.custom(ctx) else null
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
        player?.let { runCatching { it.release() } }
        player = null
        prepared = false
        seeking = false
    }
}
