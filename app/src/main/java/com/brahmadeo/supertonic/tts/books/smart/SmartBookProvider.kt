package com.brahmadeo.supertonic.tts.books.smart

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The KOReader plugin's view of a retelling, without opening MyTTS screens: ContentResolver.call(«state») gives what is
 * made and read now, and «pause» / «resume» / «stop» / «replay» control the speech. The plugin asks only while its
 * retelling window is open. A retelling is started by [SmartBookCommandActivity] (a foreground service needs it). */
class SmartBookProvider : ContentProvider() {
    private val main = Handler(Looper.getMainLooper())
    /** One at a time, in the order KOReader sent them. */
    private val background = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "SmartBookProvider").apply { isDaemon = true } }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        return when (method) {
            "state" -> {
                val s = SmartRecap.state.value
                val text = s.result ?: s.partial.takeUnless { it.trimStart().startsWith("{") }.orEmpty()
                Bundle().apply {
                    putString("title", s.kind?.title.orEmpty())
                    putString("book", s.book)
                    putString("stage", if (s.running) s.stage + (if (s.total > 0) " · ${s.done}/${s.total}" else "") else "")
                    putString("text", SmartPrompts.display(text))
                    putString("error", s.error.orEmpty())
                    putInt("running", if (s.running) 1 else 0)
                    putInt("speaking", if (SmartSpeech.speaking.value) 1 else 0)
                    putInt("paused", if (SmartSpeech.paused) 1 else 0)
                }
            }
            // The pages KOReader is about to read aloud: prepared by the LLM ahead (KoreaderAhead).
            // arg: JSON {"text": the text from the place on, "sentences": [the exact sentences it will send]}.
            // Only KOReader: the text goes to the user's cloud LLM, another app must not spend its quota. Queued in the
            // background, so a page turn in KOReader never waits for it.
            "prepare" -> {
                val uid = android.os.Binder.getCallingUid()
                val reader = ctx.packageManager.getPackagesForUid(uid).orEmpty().any { it.startsWith("org.koreader") }
                if (reader) arg?.takeIf { it.isNotBlank() }?.let { raw ->
                    background.execute {
                        runCatching {
                            val o = org.json.JSONObject(raw)
                            val list = o.optJSONArray("sentences")
                            val sentences = if (list == null) emptyList() else (0 until list.length()).map { list.optString(it) }.filter { it.isNotBlank() }
                            com.brahmadeo.supertonic.tts.llm.KoreaderAhead.readerUid = uid
                            com.brahmadeo.supertonic.tts.llm.KoreaderAhead.prepare(ctx, o.optString("text").take(60_000), sentences.take(600))
                        }
                    }
                }
                Bundle()
            }
            "pause", "resume", "stop", "replay" -> {
                onMain {
                    when (method) {
                        "pause" -> SmartSpeech.pause()
                        "resume" -> SmartSpeech.resume()
                        "stop" -> { SmartRecap.cancel(); SmartSpeech.stop() }
                        else -> SmartRecap.state.value.result?.let { SmartSpeech.speak(ctx, it) }
                    }
                }
                Bundle()
            }
            else -> null
        }
    }

    /** SmartSpeech lives on the main thread; the caller waits a moment so the next «state» already sees the change. */
    private fun onMain(action: () -> Unit) {
        val done = CountDownLatch(1)
        main.post { try { action() } finally { done.countDown() } }
        done.await(2, TimeUnit.SECONDS)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
