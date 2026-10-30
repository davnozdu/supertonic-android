package com.brahmadeo.supertonic.tts

/** Нужен только для того, чтобы свои инструкции (PromptOverrides) читались в любом процессе:
 * в основном, в :gemma_npu и в службе озвучивания, без передачи Context через полкода. */
class MyTtsApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        com.brahmadeo.supertonic.tts.llm.PromptOverrides.attach(this)
    }
}
