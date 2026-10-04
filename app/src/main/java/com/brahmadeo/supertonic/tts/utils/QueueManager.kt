package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class QueueItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val lang: String,
    val stylePath: String,
    val speed: Float,
    val steps: Int = 5,
    val startIndex: Int = 0
)

object QueueManager {
    private val queue = ArrayDeque<QueueItem>()
    private var listeners = mutableListOf<(List<QueueItem>) -> Unit>()
    private var appContext: Context? = null
    private const val FILE_NAME = "playback_queue.json"

    @Synchronized fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadQueue()
    }

    @Synchronized fun add(item: QueueItem) {
        queue.addLast(item)
        saveQueue()
        notifyListeners()
    }

    @Synchronized fun addNext(item: QueueItem) {
        queue.addFirst(item)
        saveQueue()
        notifyListeners()
    }

    @Synchronized fun next(): QueueItem? {
        val item = queue.removeFirstOrNull()
        if (item != null) {
            saveQueue()
            notifyListeners()
        }
        return item
    }

    @Synchronized fun peek(): QueueItem? {
        return queue.firstOrNull()
    }

    @Synchronized fun clear() {
        queue.clear()
        saveQueue()
        notifyListeners()
    }

    @Synchronized fun isEmpty(): Boolean = queue.isEmpty()

    @Synchronized fun size(): Int = queue.size

    @Synchronized fun getList(): List<QueueItem> = queue.toList()

    @Synchronized fun addListener(listener: (List<QueueItem>) -> Unit) {
        listeners.add(listener)
        listener(getList())
    }

    @Synchronized fun removeListener(listener: (List<QueueItem>) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized fun replaceAll(newItems: List<QueueItem>) {
        queue.clear()
        queue.addAll(newItems)
        saveQueue()
        notifyListeners()
    }

    private fun notifyListeners() {
        val list = getList()
        listeners.forEach { it(list) }
    }

    private fun saveQueue() {
        val context = appContext ?: return
        try {
            val jsonArray = JSONArray()
            queue.forEach { item ->
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("text", item.text)
                obj.put("lang", item.lang)
                obj.put("stylePath", item.stylePath)
                obj.put("speed", item.speed.toDouble())
                obj.put("steps", item.steps)
                obj.put("startIndex", item.startIndex)
                jsonArray.put(obj)
            }
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadQueue() {
        val context = appContext ?: return
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return

        try {
            val jsonString = file.readText()
            val jsonArray = JSONArray(jsonString)
            queue.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                queue.add(QueueItem(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    text = obj.getString("text"),
                    lang = obj.getString("lang"),
                    stylePath = obj.getString("stylePath"),
                    speed = obj.getDouble("speed").toFloat(),
                    steps = obj.optInt("steps", 5),
                    startIndex = obj.optInt("startIndex", 0)
                ))
            }
            // Don't notify here to avoid triggering listeners before UI is ready,
            // but for initial load it might be fine.
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
