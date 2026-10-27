package com.brahmadeo.supertonic.tts.books.smart

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.brahmadeo.supertonic.tts.QuickReadActivity

/** «Умная книга» in the quick settings: always at hand over a reader and on the lock screen (a notification's heads-up
 * hides after a few seconds). A tap opens the panel with «Ранее в книге» / «Эта глава» / «Чем закончится». */
class SmartBookTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val place = SmartMatcher.last(this)?.key?.let { SmartLibrary.book(this, it)?.title }
        tile.state = Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = place ?: "Пересказ книги"
        tile.updateTile()
    }

    override fun onClick() {
        val intent = Intent(this, QuickReadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 5431, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
        if (isLocked) unlockAndRun(open) else open()
    }
}
