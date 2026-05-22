package com.brahmadeo.supertonic.tts.tflite

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Wraps the INT8 vector_estimator.onnx via the onnxruntime-android Java API.
 * Used by the TFLite-hybrid pipeline to run the diffusion loop with fixed
 * latent_length=320 to match the TFLite text_encoder / vocoder shapes.
 *
 * Inputs (per the ONNX graph — all dynamic except style_ttl):
 *   noisy_latent:  float32 [1, 144, latentLen]
 *   text_emb:      float32 [1, 256, textLen]  (channels-first)
 *   style_ttl:     float32 [1, 50, 256]
 *   latent_mask:   float32 [1, 1, latentLen]
 *   text_mask:     float32 [1, 1, textLen]
 *   current_step:  float32 [1]
 *   total_step:    float32 [1]
 * Output: denoised_latent [1, 144, latentLen]
 */
class OrtVectorEstimator(modelFile: File) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(6)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Match the Rust pipeline: XNNPACK kernels for ARM Conv/MatMul.
            try {
                addXnnpack(mapOf("intra_op_num_threads" to "6"))
            } catch (_: Throwable) {
            }
        }
        session = env.createSession(modelFile.absolutePath, opts)
    }

    /**
     * Run the full diffusion loop in one call so the constant tensors
     * (text_emb, style_ttl, latent_mask, text_mask, total_step) only get
     * marshalled to native ORT memory once instead of N×7 times.
     *
     * @param shouldCancel polled before each step; returning true breaks
     * the loop and the partial denoised latent is returned.
     */
    fun runDiffusion(
        initialLatent: FloatArray,
        textEmb: FloatArray,
        styleTtl: FloatArray,
        latentMask: FloatArray,
        textMask: FloatArray,
        textLen: Int,
        latentLen: Int,
        totalSteps: Int,
        shouldCancel: () -> Boolean = { false },
    ): FloatArray {
        val textEmbT = OnnxTensor.createTensor(env, FloatBuffer.wrap(textEmb), longArrayOf(1, 256, textLen.toLong()))
        val styleTtlT = OnnxTensor.createTensor(env, FloatBuffer.wrap(styleTtl), longArrayOf(1, 50, 256))
        val latentMaskT = OnnxTensor.createTensor(env, FloatBuffer.wrap(latentMask), longArrayOf(1, 1, latentLen.toLong()))
        val textMaskT = OnnxTensor.createTensor(env, FloatBuffer.wrap(textMask), longArrayOf(1, 1, textLen.toLong()))
        val totalStepT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(totalSteps.toFloat())), longArrayOf(1))
        try {
            var xt = initialLatent
            val outBuf = FloatArray(144 * latentLen)
            for (step in 0 until totalSteps) {
                if (shouldCancel()) break
                val noisyT = OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), longArrayOf(1, 144, latentLen.toLong()))
                val curStepT = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1))
                try {
                    val feed = HashMap<String, OnnxTensor>(7).apply {
                        put("noisy_latent", noisyT)
                        put("text_emb", textEmbT)
                        put("style_ttl", styleTtlT)
                        put("latent_mask", latentMaskT)
                        put("text_mask", textMaskT)
                        put("current_step", curStepT)
                        put("total_step", totalStepT)
                    }
                    session.run(feed).use { result ->
                        val tensor = result.get("denoised_latent").get() as OnnxTensor
                        tensor.floatBuffer.get(outBuf)
                        xt = outBuf.copyOf()
                    }
                } finally {
                    noisyT.close(); curStepT.close()
                }
            }
            return xt
        } finally {
            textEmbT.close(); styleTtlT.close(); latentMaskT.close(); textMaskT.close(); totalStepT.close()
        }
    }

    /**
     * One denoising step. All buffers are flat row-major; caller owns the
     * memory. textLen is the padded length of text_emb / text_mask (320 in
     * the TFLite-hybrid pipeline because the text_encoder is fixed at 320).
     *
     * @return new denoised latent, shape [144 * 320] flat
     */
    override fun close() {
        session.close()
    }
}
