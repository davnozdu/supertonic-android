package com.brahmadeo.supertonic.tts.books.smart

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.brahmadeo.supertonic.tts.QuickReadActivity

/** «Умная книга» from a reader plugin (KOReader audiobook.koplugin): an invisible step that takes the open book's page
 * text (it finds the book and the place exactly, by its sentences), and optionally a retelling to make. The reader stays
 * on screen; the retelling is made and read by [SmartRecapService]. Nothing runs between such commands.
 * Extras: «text» — the current page, «path» — the book file, «kind» — BEFORE / PREVIOUS / CHAPTER / WHO / ENDING, or none
 * to only remember the place (a book closed or the reader put in the background); «question» (with «sentence», the one
 * heard last) — «Спросить о книге», answered also for a book MyTTS does not know, from the page. */
class SmartBookCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { handle(intent) } catch (_: Exception) { } finally { finish(); overridePendingTransition(0, 0) }
    }

    private fun handle(intent: Intent) {
        // The plugin's window controls the speech this way when it cannot reach SmartBookProvider.
        when (intent.getStringExtra("control")) {
            "pause" -> return SmartSpeech.pause()
            "resume" -> return SmartSpeech.resume()
            "stop" -> { SmartRecap.cancel(); return SmartSpeech.stop() }
            "replay" -> return SmartRecap.state.value.result?.let { SmartSpeech.speak(this, it) } ?: Unit
        }
        if (!SmartMatcher.enabled(this)) return toast("«Умная книга» выключена в MyTTS.")
        val text = intent.getStringExtra("text").orEmpty().take(MAX_TEXT)
        val kind = intent.getStringExtra("kind")?.let { k -> SmartRecap.Kind.entries.firstOrNull { it.name == k } }
        val place = SmartMatcher.locate(this, text)
        // The question by voice: MyTTS's listening window over the reader (it asks by itself once recognised).
        if (intent.getStringExtra("voice") == "1") {
            startActivity(Intent(this, com.brahmadeo.supertonic.tts.asr.VoiceAskActivity::class.java)
                .putExtra("text", text).putExtra("sentence", intent.getStringExtra("sentence").orEmpty())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val question = intent.getStringExtra("question")?.trim().orEmpty()
        if (question.isNotEmpty()) {
            if (!SmartRecap.questions(this)) return toast("Вопросы о книге выключены: MyTTS → «Умная книга» → «Вопросы о книге».")
            if (place != null) SmartMatcher.choose(this, place)
            val speak = SmartRecap.answerSpoken(this)
            SmartSpeech.stop()
            val last = intent.getStringExtra("sentence").orEmpty().ifBlank { SmartMatcher.lastHeard }
            if (SmartRecap.ask(this, question, place, text, last)) SmartRecapService.follow(this, speak)
            if (intent.getStringExtra("window") != "1") toast("MyTTS: ${SmartRecap.Kind.ASK.title}…")
            return
        }
        if (place == null) {
            // Maybe a book just put into the folder: the next command will find it.
            SmartFolder.scanForUnknown(this)
            if (kind != null) toast(if (SmartFolder.folder(this) == null)
                "MyTTS не знает эту книгу: выберите папку с книгами в меню MyTTS → «Умная книга»."
                else "MyTTS не нашёл эту книгу в папке «Умной книги». Если она там, повторите через минуту.")
            return
        }
        SmartMatcher.choose(this, place)
        if (kind == null) return
        val speak = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).getBoolean("smart_book_speak", true)
        SmartSpeech.stop()
        SmartRecap.start(this, kind, place)
        SmartRecapService.follow(this, speak)
        // The plugin draws its own window with the text and the controls («window»); otherwise MyTTS's panel shows the text
        // when it is not read aloud.
        if (intent.getStringExtra("window") == "1") return
        if (!speak) startActivity(Intent(this, QuickReadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else toast("MyTTS: ${kind.title}…")
    }

    private fun toast(message: String) { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }

    companion object { private const val MAX_TEXT = 8000 }
}
