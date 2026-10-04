package com.brahmadeo.supertonic.tts.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

object ReadingNotificationChannels {
    const val PANEL = "quick_read_controls_v2"
    const val PLAYBACK = "supertonic_playback_v2"
    fun create(manager: NotificationManager, id: String, oldId: String, name: String) {
        if (Build.VERSION.SDK_INT < 26) return
        val old = manager.getNotificationChannel(oldId)
        // Keep an explicit user choice, including disabling the previous channel.
        val importance = if (old != null && (old.importance == NotificationManager.IMPORTANCE_NONE ||
                    (Build.VERSION.SDK_INT >= 29 && old.hasUserSetImportance()))) old.importance
            else NotificationManager.IMPORTANCE_HIGH
        manager.createNotificationChannel(NotificationChannel(id, name, importance).apply {
            description = "Управление чтением MyTTS, без звука и вибрации"
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
    }
}
