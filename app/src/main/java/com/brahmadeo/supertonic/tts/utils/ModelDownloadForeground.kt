package com.brahmadeo.supertonic.tts.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** Scoped data-sync service: downloads remain network-eligible after the screen locks. */
object ModelDownloadForeground {
    private var users=0
    private var ready=CompletableDeferred<Unit>()
    @Synchronized fun started() { ready.complete(Unit) }
    suspend fun <T> run(context: Context, work: suspend () -> T): T {
        val ctx=context.applicationContext
        val signal=synchronized(this) {
            if(users==0) ready=CompletableDeferred()
            users++
            ready
        }
        try {
            ContextCompat.startForegroundService(ctx,Intent(ctx,ModelDownloadService::class.java))
            withTimeout(5000) { signal.await() }
            return work()
        } finally {
            synchronized(this) { users--;if(users==0) ctx.stopService(Intent(ctx,ModelDownloadService::class.java)) }
        }
    }
}

class ModelDownloadService : Service() {
    private var wake: PowerManager.WakeLock?=null
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager=getSystemService(NotificationManager::class.java)
        if(android.os.Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(NotificationChannel("tts_model_download","Установка моделей, голосов и музыки",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,com.brahmadeo.supertonic.tts.MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(4205,NotificationCompat.Builder(this,"tts_model_download")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("MyTTS: установка моделей, голосов и музыки")
            .setContentText("Загрузка и проверка файлов. Продолжается при выключенном экране.")
            .setOngoing(true).setContentIntent(open).setProgress(0,0,true).build())
        if(wake?.isHeld!=true) wake=(getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Supertonic:ModelDownload").apply { acquire(10*60*1000L) }
        ModelDownloadForeground.started()
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
    override fun onDestroy() { if(wake?.isHeld==true) wake?.release();wake=null;super.onDestroy() }
}
