package com.brahmadeo.supertonic.tts.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.brahmadeo.supertonic.tts.QuickReadActivity
import com.brahmadeo.supertonic.tts.R

object ReadingControls {
    private val main = Handler(Looper.getMainLooper())
    private data class Controls(val pause: () -> Unit, val stop: () -> Unit, val play: () -> Unit, val paragraph: (Int) -> Unit)
    private val listeners = LinkedHashMap<Any, Controls>()
    @Volatile private var externalReading = false
    @Volatile private var paused = false
    fun register(ctx: Context, owner: Any, pause: () -> Unit, stop: () -> Unit, play: () -> Unit = {}, paragraph: (Int) -> Unit = {}) { main.post {
        listeners[owner] = Controls(pause, stop, play, paragraph); show(ctx)
    } }
    fun unregister(owner: Any) { main.post { listeners.remove(owner) } }
    fun externalStarted(ctx: Context) { externalReading = true; paused = false; main.post { show(ctx) } }
    fun internalStarted() { externalReading = false; paused = false }
    fun audioState(ctx: Context, playing: Boolean) { if (externalReading) ReadingIsland.state(ctx, playing || paused, playing && !paused) }
    fun play(ctx: Context) { main.post {
        SleepTimer.manualResume()
        paused = false
        if (externalReading) {
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        } else listeners.values.toList().forEach { runCatching { it.play() } }
    } }
    fun paragraph(ctx: Context, direction: Int) { main.post {
        if (externalReading) android.widget.Toast.makeText(ctx, "Переходы по абзацам доступны для статей внутри MyTTS.", android.widget.Toast.LENGTH_SHORT).show()
        else listeners.values.toList().forEach { runCatching { it.paragraph(direction) } }
    } }
    fun pause(ctx: Context) = command(ctx, false)
    fun stop(ctx: Context) = command(ctx, true)
    private fun command(ctx: Context, stop: Boolean) { main.post {
        SleepTimer.block()
        paused = !stop
        if (stop) { com.brahmadeo.supertonic.tts.article.ArticleSession.cancel(); com.brahmadeo.supertonic.tts.utils.QueueManager.clear() }
        // Stop the requesting reader before flushing its queued Android audio.
        if (externalReading) {
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val key = if (stop) KeyEvent.KEYCODE_MEDIA_STOP else KeyEvent.KEYCODE_MEDIA_PAUSE
            runCatching {
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            }
        }
        listeners.values.toList().forEach { callbacks -> runCatching { if (stop) callbacks.stop() else callbacks.pause() } }
        com.brahmadeo.supertonic.tts.music.BackgroundMusic.stopTts()
        ReadingIsland.state(ctx, !stop, false)
    } }
    fun panel(ctx: Context, paste: Boolean = false): PendingIntent = PendingIntent.getActivity(ctx, if(paste) 5412 else 5411,
        Intent(ctx, QuickReadActivity::class.java).putExtra("paste", paste), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun show(ctx: Context) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("quick_read", "Быстрое чтение", NotificationManager.IMPORTANCE_LOW))
        fun command(action: String, id: Int) = PendingIntent.getBroadcast(ctx, id,
            Intent(ctx, ReadingControlReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { manager.notify(5411, NotificationCompat.Builder(ctx, "quick_read")
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("MyTTS · быстрое чтение")
            .setContentText("Текст или ссылка · управление чтением · таймер сна")
            .setContentIntent(panel(ctx)).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_edit, "Вставить", panel(ctx, true))
            .addAction(android.R.drawable.ic_media_pause, "Пауза", command("pause", 5413))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", command("stop", 5414)).build()) }
    }
}
class ReadingControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "stop") ReadingControls.stop(context)
        else if (intent.action == "pause") ReadingControls.pause(context)
    }
}
