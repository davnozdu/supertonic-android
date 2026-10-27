package com.brahmadeo.supertonic.tts.books.smart

import android.app.Activity
import android.os.Bundle
import com.brahmadeo.supertonic.tts.service.ReaderApp

/** «Продолжить чтение» after a retelling or an answer: the speech stops, the book goes on, and the reader comes back to the
 * screen. An invisible step started straight by the notification or the panel (only an activity in front may bring
 * another app forward). */
class ReaderReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { SmartRecapService.resume(this) }
        runCatching { ReaderApp.open(this) }
        finish()
        overridePendingTransition(0, 0)
    }
}
