package com.brahmadeo.supertonic.tts.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.brahmadeo.supertonic.tts.utils.ResumableModelFile
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Voice questions («Спросить о книге» by voice): GigaAM-v3 e2e CTC (Sber, MIT) recognises Russian speech on the phone,
 * with punctuation. The ONNX export of istupakov/gigaam-v3-onnx, int8, pinned by revision, size and SHA-256; downloaded
 * only when the listener asks for it. Loaded for a question, unloaded two minutes after the last one. */
object GigaAm {
    private const val REVISION = "322c3b29492673eb7d0b434bfa9dfb8653e34d02"
    private const val BASE = "https://huggingface.co/istupakov/gigaam-v3-onnx/resolve/$REVISION"
    private data class Asset(val name: String, val size: Long, val sha: String)
    private val MODEL = Asset("v3_e2e_ctc.int8.onnx", 224_893_347L, "2e3fcb7a7b66030336fd10c2fcfb033bd1dc7e1bf238fe5cfd83b1d0cfc9d28e")
    private val VOCAB = Asset("v3_e2e_ctc_vocab.txt", 2_007L, "142de7570b3de5b3035ce111a89c228e80e6085273731d944093ddf24fa539cd")
    const val SIZE_MB = 215

    private fun dir(context: Context) = File(context.applicationContext.noBackupFilesDir, "asr/gigaam-v3")
    private fun marker(context: Context) = File(dir(context), "verified-$REVISION")

    fun ready(context: Context) = marker(context).isFile && File(dir(context), MODEL.name).length() == MODEL.size

    /** Downloads (resuming) and checks both files; [progress] gets bytes done and total. */
    suspend fun download(context: Context, progress: (Long, Long) -> Unit) {
        val d = dir(context).apply { mkdirs() }
        val total = MODEL.size + VOCAB.size
        ResumableModelFile.fetch("$BASE/${VOCAB.name}", File(d, VOCAB.name), VOCAB.size, VOCAB.sha) { _, _ -> }
        ResumableModelFile.fetch("$BASE/${MODEL.name}", File(d, MODEL.name), MODEL.size, MODEL.sha) { got, _ -> progress(VOCAB.size + got, total) }
        marker(context).writeText(MODEL.sha)
        progress(total, total)
    }

    fun delete(context: Context) { unload(); dir(context).deleteRecursively() }

    private var session: OrtSession? = null
    private var vocab: List<String> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private val idle = Runnable { unload() }

    @Synchronized private fun unload() { session?.close(); session = null }

    /** The text said in [samples] (16 kHz mono, −1…1), punctuated; empty when nothing was recognised. */
    @Synchronized fun recognize(context: Context, samples: FloatArray): String {
        main.removeCallbacks(idle)
        try {
            val s = session ?: run {
                val d = dir(context)
                vocab = File(d, VOCAB.name).readLines(Charsets.UTF_8).filter { it.isNotEmpty() }.map { it.substringBeforeLast(' ') }
                OrtSession.SessionOptions().use { o ->
                    o.setIntraOpNumThreads(4)
                    o.addConfigEntry("session.intra_op.allow_spinning", "0")
                    o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    OrtEnvironment.getEnvironment().createSession(File(d, MODEL.name).path, o)
                }.also { session = it }
            }
            val frames = GigaAmFeatures.frames(samples.size)
            if (frames < 10) return ""
            val env = OrtEnvironment.getEnvironment()
            val features = GigaAmFeatures.compute(samples)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, GigaAmFeatures.MELS.toLong(), frames.toLong())).use { f ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1)).use { l ->
                    s.run(mapOf("features" to f, "feature_lengths" to l)).use { out ->
                        val tensor = out[0] as OnnxTensor
                        val shape = tensor.info.shape
                        val buffer = tensor.floatBuffer
                        val values = FloatArray(buffer.remaining()).also { buffer.get(it) }
                        return GigaAmFeatures.decode(values, shape[1].toInt(), vocab)
                    }
                }
            }
        } finally { main.postDelayed(idle, 120_000L) }
    }
}
