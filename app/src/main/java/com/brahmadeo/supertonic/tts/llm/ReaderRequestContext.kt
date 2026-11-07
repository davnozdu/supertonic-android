package com.brahmadeo.supertonic.tts.llm

import com.brahmadeo.supertonic.tts.books.ReplyIndex

/** Capture context when a client queues a request, not after lookahead has advanced past it. */
internal class ReaderRequestContext(private val perClientLimit: Int = 256, private val clientLimit: Int = 8) {
    private data class Request(val text: String, val before: List<String>)
    private class Client(var tail: List<String> = emptyList(), val requests: MutableList<Request> = mutableListOf())
    private val clients = linkedMapOf<Int, Client>()
    @Synchronized fun record(uid: Int, text: String): List<String> {
        val client = clients.getOrPut(uid) { Client() }
        while (clients.size > clientLimit) clients.remove(clients.keys.first())
        val before = client.tail
        client.tail = (before + ReplyIndex.words(text)).takeLast(ReplyIndex.WINDOW - 1)
        client.requests += Request(text, before)
        while (client.requests.size > perClientLimit) client.requests.removeAt(0)
        return before
    }
    @Synchronized fun take(uid: Int, text: String): List<String> {
        val client = clients[uid]
        val at = client?.requests?.indexOfFirst { it.text == text } ?: -1
        if (at >= 0) return client!!.requests.removeAt(at).before
        // Observer unavailable: synthesis itself still advances this client's own history.
        val before = record(uid, text)
        clients[uid]?.requests?.removeLastOrNull()
        return before
    }
    @Synchronized fun clear(uid: Int) { clients.remove(uid) }
    @Synchronized fun clear() { clients.clear() }
}
