package com.brahmadeo.supertonic.tts.ambience

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlin.concurrent.thread

/** Генерация пачки звуков в foreground-сервисе.
 *
 * Зачем сервис: пока работа шла в активности, Android опускал процесс в фоновую группу, как только экран
 * уходил с диагностического окна, — и декодер замедлялся в три-четыре раза (1,2 с → 4,4 с на клип).
 * FGS держит процесс в приоритете и позволяет гасить экран. Экстрасы те же, что у пробы. */
class SoundGenService : Service() {
    private var job: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Генерация звуков", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder.setContentTitle("MyTTS · генерация звуков")
            .setContentText("Идёт расчёт, экран можно гасить")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).setOnlyAlertOnce(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, notification)
        // Экран погашен → система уходит в Doze и придерживает расчёт (замер: клип 0,9 с превращался в 4 с, одна
        // пауза длилась две минуты). Wake lock не даёт уснуть: работа идёт с той же скоростью, что и при экране.
        // Дольше двух часов проба не идёт; при бесконечной подготовке его обновляет AmbienceGenerationService.
        if (wake?.isHeld != true) wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyTTS:Sounds").apply {
                // Генерация базы идёт часами и без компьютера: снимок на 12 ч; обычная проба — 2 ч.
                acquire((if (intent.getBooleanExtra("ambienceBankJob", false)) 12L else 2L) * 60 * 60 * 1000L) }
        if (job?.isAlive == true) {
            Log.i(TAG, "a batch is already running, this start is ignored")
            return START_NOT_STICKY
        }
        job = thread(name = "sound-gen") {
            try {
                // The generator runs in its own process: the HTP turn must be taken from the main process, otherwise
                // its QNN graphs would run at the same time as the reader's (QNN 1002, both sides slower).
                com.brahmadeo.supertonic.tts.utils.HtpGate.attach(this)
                AmbienceProbe.run(this, intent, recycle = { restartLater(intent) })
            } catch (e: Throwable) {
                Log.e(TAG, "FAILED ${e.javaClass.simpleName}: ${e.message}", e)
                // Журнал приложения прошивка глушит: причину — в файл, его читает скрипт сборки базы.
                runCatching {
                    java.io.File(filesDir, "bank-gen").apply { mkdirs() }.let { d ->
                        java.io.File(d, "error.txt").writeText("${java.util.Date()}\n${android.util.Log.getStackTraceString(e)}")
                    }
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.interrupt()
        if (wake?.isHeld == true) wake?.release()
        wake = null
        super.onDestroy()
        // This service is the only component of ":soundgen": ending the thread would keep the process (and the NPU
        // buffers the kernel still caches for it) alive. The job's progress is in files, so a restart resumes.
        Log.i(TAG, "generator process done: ${com.brahmadeo.supertonic.tts.utils.Npu.footprint(this)}")
        com.brahmadeo.supertonic.tts.utils.HtpGate.detach("job finished")
    }

    /**
     * A long run gets a fresh process: the alarm starts this service again a few seconds after this process is gone, and
     * done.txt makes the new one continue where this one stopped. Scheduling is best effort — on «false» the run simply
     * goes on here, so a restrictive ROM cannot break generation, it only keeps the process longer.
     */
    private fun restartLater(intent: Intent): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 26) return false
        val again = Intent(intent).setAction(ACTION_RESTART)
        val pending = android.app.PendingIntent.getForegroundService(this, ID, again,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
            .setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5000, pending)
        Log.i(TAG, "next batch in a fresh process in 5 s (memory and dmabuf are returned on the way out)")
        true
    }.getOrDefault(false)

    private companion object {
        const val TAG = "SoundGen"
        const val CHANNEL = "sound_gen"
        const val ID = 4211
        const val ACTION_RESTART = "com.brahmadeo.supertonic.tts.RESTART_BANK"
    }
}
