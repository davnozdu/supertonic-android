package com.brahmadeo.supertonic.tts.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.brahmadeo.supertonic.tts.SleepTimerActivity
import com.brahmadeo.supertonic.tts.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One timer for the built-in player and Android TTS clients. No polling during synthesis. */
object SleepTimer {
    private const val ID = 5314
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var deadline = 0L
    private var bootWall = 0L
    private val state = MutableStateFlow("Выключен")
    val status = state.asStateFlow()
    @Volatile var blocked = false; private set
    @Volatile var finishAtEnd = false; private set
    private val expire = Runnable { checkDeadline() }
    private val refreshStatus = object : Runnable {
        override fun run() {
            if (deadline > 0) {
                state.value = "До остановки: ${remainingLabel()}"
                main.postDelayed(this, 60_000)
            }
        }
    }

    fun initialize(ctx: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { initialize(ctx) }; return }
        if (context != null) return
        context = ctx.applicationContext
        val prefs = ctx.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE)
        val savedWall = prefs.getLong("bootWall", 0)
        val currentBootWall = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        // An elapsed deadline from before a reboot is invalid.
        deadline = if (kotlin.math.abs(currentBootWall - savedWall) < 60_000) prefs.getLong("deadline", 0) else 0
        bootWall = currentBootWall
        finishAtEnd = prefs.getBoolean("atEnd", false) && deadline == 0L
        update()
    }
    fun setMinutes(ctx: Context, minutes: Int) { main.post {
        initialize(ctx); blocked = false; finishAtEnd = false
        deadline = SystemClock.elapsedRealtime() + minutes.coerceIn(1, 720) * 60_000L
        persist(); update()
    } }
    fun setAtEnd(ctx: Context) { main.post {
        initialize(ctx); blocked = false; deadline = 0; finishAtEnd = true
        persist(); update()
    } }
    fun cancel(ctx: Context) { main.post {
        initialize(ctx); blocked = false; deadline = 0; finishAtEnd = false
        persist(); update()
    } }
    fun manualResume() { blocked = false }
    fun block() { blocked = true }
    fun remainingLabel(): String = if (deadline > 0) "${((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 59_999) / 60_000} мин." else ""
    fun islandEnabled(ctx: Context) = ctx.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE).getBoolean("island", false)
    fun setIsland(ctx: Context, enabled: Boolean) { main.post {
        initialize(ctx); ctx.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE).edit { putBoolean("island", enabled) }
        update()
    } }
    fun completedText() { main.post { if (finishAtEnd) fire() } }
    fun checkDeadline() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { checkDeadline() }; return }
        if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) fire()
    }
    private fun fire() {
        deadline = 0; finishAtEnd = false; blocked = true
        persist(); update()
        state.value = "Чтение остановлено таймером"
        context?.let { ReadingControls.pause(it) }
        android.util.Log.i("SleepTimer", "Expired: speech and music paused")
    }
    private fun persist() { context?.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE)?.edit {
        putLong("deadline", deadline); putLong("bootWall", bootWall); putBoolean("atEnd", finishAtEnd)
    } }
    fun settingsIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, ID,
        Intent(ctx, SleepTimerActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun update() {
        val ctx = context ?: return
        main.removeCallbacks(expire)
        main.removeCallbacks(refreshStatus)
        val alarm = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val trigger = PendingIntent.getBroadcast(ctx, ID, Intent(ctx, SleepTimerReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarm.cancel(trigger)
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (deadline == 0L && !finishAtEnd) { state.value = "Выключен"; manager.cancel(ID); return }
        if (deadline > 0) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            state.value = "До остановки: ${(remaining + 59_999) / 60_000} мин."
            main.postDelayed(expire, remaining)
            main.postDelayed(refreshStatus, minOf(remaining, 60_000))
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, trigger)
        } else state.value = "До конца текста / очереди MyTTS"
        if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("sleep_timer", "Таймер сна", NotificationManager.IMPORTANCE_LOW))
        val builder = NotificationCompat.Builder(ctx, "sleep_timer")
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Таймер сна · MyTTS")
            .setContentText(state.value).setContentIntent(settingsIntent(ctx)).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_recent_history, "Изменить таймер", settingsIntent(ctx))
        if (deadline > 0) builder.setWhen(System.currentTimeMillis() + (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            .setUsesChronometer(true).setChronometerCountDown(true)
        if (deadline > 0 && android.os.Build.VERSION.SDK_INT >= 36 && islandEnabled(ctx))
            builder.setOngoing(true).setRequestPromotedOngoing(true)
        runCatching { manager.notify(ID, builder.build()) }
    }
}

class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SleepTimer.initialize(context); SleepTimer.checkDeadline()
    }
}
