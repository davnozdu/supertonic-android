package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryItem(
    val text: String,
    val timestamp: Long,
    val dateString: String,
    val voiceName: String
)

/** Session history in RAM only: nothing about read texts is written to flash. */
object HistoryManager {
    private const val MAX_ITEMS = 100
    private val items = ArrayList<HistoryItem>()

    @Suppress("UNUSED_PARAMETER")
    @Synchronized fun saveItem(context: Context, text: String, voiceName: String) {
        items.removeAll { it.text == text }
        val timestamp = System.currentTimeMillis()
        val dateString = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(timestamp))
        items.add(0, HistoryItem(text, timestamp, dateString, voiceName))
        while (items.size > MAX_ITEMS) items.removeAt(items.lastIndex)
    }

    @Suppress("UNUSED_PARAMETER")
    @Synchronized fun loadHistory(context: Context): List<HistoryItem> = items.toList()

    @Suppress("UNUSED_PARAMETER")
    @Synchronized fun deleteItem(context: Context, item: HistoryItem) {
        items.removeAll { it.timestamp == item.timestamp && it.text == item.text }
    }

    @Suppress("UNUSED_PARAMETER")
    @Synchronized fun clearHistory(context: Context) { items.clear() }
}
