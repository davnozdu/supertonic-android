package com.brahmadeo.supertonic.tts.tflite

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * Wraps the original FP32 vocoder.onnx so the hybrid pipeline can A/B
 * against the INT4 TFLite vocoder. Input shape is dynamic — we feed only
 * the valid latentLen prefix instead of padding to 320, which avoids
 * leaking vocoder output for masked-out latent positions.
 *
 * Inputs:  latent float32 [1, 144, latent_length]
 * Outputs: wav_tts float32 [1, latent_length * CHUNK_SIZE]
 */
class OrtVocoder(modelFile: File) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelFile.absolutePath, opts)
    }

    /**
     * @param latent flat row-major [1, 144, latentLen]
     * @return wav PCM samples as FloatArray, length = latentLen * 3072
     */
    fun run(latent: FloatArray, latentLen: Int): FloatArray {
        require(latent.size == 144 * latentLen) {
            "latent must be ${144 * latentLen}, got ${latent.size}"
        }
        val input = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(latent), longArrayOf(1, 144, latentLen.toLong())
        )
        try {
            session.run(mapOf("latent" to input)).use { result ->
                val out = result.get("wav_tts").get() as OnnxTensor
                val total = out.info.shape.fold(1L) { a, b -> a * b }.toInt()
                val flat = FloatArray(total)
                out.floatBuffer.get(flat)
                return flat
            }
        } finally {
            input.close()
        }
    }

    override fun close() {
        session.close()
    }
}
