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
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyTTS:Sounds").apply { acquire(2 * 60 * 60 * 1000L) }
        if (job?.isAlive == true) {
            Log.i(TAG, "a batch is already running, this start is ignored")
            return START_NOT_STICKY
        }
        job = thread(name = "sound-gen") {
            try {
                AmbienceProbe.run(this, intent)
            } catch (e: Throwable) {
                Log.e(TAG, "FAILED ${e.javaClass.simpleName}: ${e.message}", e)
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
    }

    private companion object {
        const val TAG = "SoundGen"
        const val CHANNEL = "sound_gen"
        const val ID = 4211
    }
}
