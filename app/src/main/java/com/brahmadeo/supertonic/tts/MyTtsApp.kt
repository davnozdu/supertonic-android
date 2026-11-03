package com.brahmadeo.supertonic.tts

/** Нужен только для того, чтобы свои инструкции (PromptOverrides) читались в любом процессе:
 * в основном, в :gemma_npu и в службе озвучивания, без передачи Context через полкода. */
class MyTtsApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        com.brahmadeo.supertonic.tts.llm.PromptOverrides.attach(this)
        // The NPU cleaner runs in the main process only: the worker (:soundgen) and Gemma's process are meant to die
        // with their job, and closing models there would fight the job that owns them.
        val process = com.brahmadeo.supertonic.tts.utils.Npu.processName()
        if (process.isEmpty() || process == packageName) {
            com.brahmadeo.supertonic.tts.utils.NpuJanitor.start(
                this,
                release = { com.brahmadeo.supertonic.tts.SupertonicTTS.release() },
                idle = { com.brahmadeo.supertonic.tts.SupertonicTTS.idleMs() })
            // Progress of the sound generator, which now runs in ":soundgen", reaches the screens through broadcasts.
            com.brahmadeo.supertonic.tts.ambience.AmbienceGeneration.attach(this)
        }
    }
}
