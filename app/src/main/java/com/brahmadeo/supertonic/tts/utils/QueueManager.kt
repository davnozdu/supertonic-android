package com.brahmadeo.supertonic.tts.utils

import android.content.Context
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

/** Playback queue in RAM only; a finished item is not needed after the process ends. */
object QueueManager {
    private val queue = ArrayDeque<QueueItem>()
    private var listeners = mutableListOf<(List<QueueItem>) -> Unit>()
    private var appContext: Context? = null

    @Synchronized fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        ReadingMemory.purgeLegacy(context)
    }

    @Synchronized fun add(item: QueueItem) {
        queue.addLast(item)
        notifyListeners()
    }

    @Synchronized fun addNext(item: QueueItem) {
        queue.addFirst(item)
        notifyListeners()
    }

    @Synchronized fun next(): QueueItem? {
        val item = queue.removeFirstOrNull()
        if (item != null) {
            notifyListeners()
        }
        return item
    }

    @Synchronized fun peek(): QueueItem? {
        return queue.firstOrNull()
    }

    @Synchronized fun clear() {
        queue.clear()
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
        notifyListeners()
    }

    private fun notifyListeners() {
        val list = getList()
        listeners.forEach { it(list) }
    }

}
