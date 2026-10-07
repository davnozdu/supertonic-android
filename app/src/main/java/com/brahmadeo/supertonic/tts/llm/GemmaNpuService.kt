package com.brahmadeo.supertonic.tts.llm

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import java.io.File

/** Gemma 4 on the Hexagon NPU in its own process (":gemma_npu"). ggml-hexagon aborts the whole process when
 * the DSP session fails ("dspqueue_read failed" after a DSP user-PD exception), which used to kill MyTTS
 * mid-book. Here it kills only this process; the reader keeps playing with the offline fallback and the
 * client restarts the service with a backoff. Its own process also gets its own DSP session, apart from
 * the QNN graphs of Kokoro/Tera; the HTP turn is still shared through [IHtpGate]. */
class GemmaNpuService : Service() {
    private var model: GemmaHexagon.Model? = null

    private val binder = object : IGemmaNpu.Stub() {
        override fun load(modelPath: String, contextTokens: Int, threads: Int, gate: IHtpGate) {
            synchronized(this@GemmaNpuService) {
                com.brahmadeo.supertonic.tts.utils.Npu.remoteHtp = gate
                if (model == null) model = GemmaHexagon.load(this@GemmaNpuService, File(modelPath), contextTokens, threads)
            }
        }
        override fun generate(prompt: String, maxTokens: Int, slot: Int): String = synchronized(this@GemmaNpuService) {
            (model ?: throw IllegalStateException("Gemma NPU не загружена")).generate(prompt, maxTokens, slot)
        }
        // Not synchronized: it must interrupt a running generate().
        override fun cancel() { model?.cancel() }
        override fun stats(): String = model?.stats().orEmpty()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Unbound = unloaded: end the process so the model, its buffers and the DSP session are released at once.
        Log.i("GemmaNpu", "Gemma NPU process stopping")
        runCatching { model?.close() }
        model = null
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }
}
