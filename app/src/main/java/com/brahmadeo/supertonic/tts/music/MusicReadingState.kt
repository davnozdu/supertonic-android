package com.brahmadeo.supertonic.tts.music

/** Track playback callbacks, not synthesis completion or speculative LLM work. */
internal class MusicReadingState {
    private data class Entry(val token: Long, val id: String?, var started: Boolean = false)
    private data class Client(val entries: MutableList<Entry> = ArrayList(), var reading: Boolean = false)
    private val clients = HashMap<Any, Client>()
    private var nextToken = 0L
    private var appPlaying = false

    @Synchronized fun enqueue(owner: Any, id: String?, flush: Boolean): Long {
        if (flush) clients.remove(owner)
        val client = clients.getOrPut(owner) { Client() }
        val token = ++nextToken
        client.entries.add(Entry(token, id))
        return token
    }
    @Synchronized fun start(owner: Any, id: String?) {
        val client = clients[owner] ?: return
        val entry = client.entries.firstOrNull { it.id == id && !it.started } ?: return
        entry.started = true
        client.reading = true
    }
    @Synchronized fun finish(owner: Any, id: String?, wasStarted: Boolean? = null) {
        val client = clients[owner] ?: return
        val entry = if(wasStarted!=null) client.entries.firstOrNull { it.id==id && it.started==wasStarted }
            else client.entries.firstOrNull { it.id==id && it.started } ?: client.entries.firstOrNull { it.id==id }
        if(entry==null) return
        client.entries.remove(entry)
        if (client.entries.isEmpty()) clients.remove(owner)
    }
    @Synchronized fun reject(owner: Any, token: Long) {
        val client = clients[owner] ?: return
        client.entries.removeAll { it.token == token }
        if (client.entries.isEmpty()) clients.remove(owner)
    }
    @Synchronized fun stop(owner: Any) { clients.remove(owner) }
    @Synchronized fun stopTts() { clients.clear() }
    @Synchronized fun app(playing: Boolean) { appPlaying = playing }
    @Synchronized fun playing(): Boolean = appPlaying || clients.values.any { it.reading && it.entries.isNotEmpty() }
}
