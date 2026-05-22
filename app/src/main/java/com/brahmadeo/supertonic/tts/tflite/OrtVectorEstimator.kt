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
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Match the Rust pipeline: XNNPACK kernels for ARM Conv/MatMul.
            // Without this EP the Java CPU EP is 5-10x slower on the
            // diffusion loop.
            try {
                addXnnpack(mapOf("intra_op_num_threads" to "4"))
            } catch (_: Throwable) {
                // Older ORT builds expose addCPU only — fall back silently.
            }
        }
        session = env.createSession(modelFile.absolutePath, opts)
    }

    /**
     * One denoising step. All buffers are flat row-major; caller owns the
     * memory. textLen is the padded length of text_emb / text_mask (320 in
     * the TFLite-hybrid pipeline because the text_encoder is fixed at 320).
     *
     * @return new denoised latent, shape [144 * 320] flat
     */
    fun step(
        noisyLatent: FloatArray,
        textEmb: FloatArray,         // [1, 256, textLen] channels-first
        styleTtl: FloatArray,        // [1, 50, 256]
        latentMask: FloatArray,      // [1, 1, latentLen]
        textMask: FloatArray,        // [1, 1, textLen]
        textLen: Int,
        latentLen: Int,
        currentStep: Int,
        totalStep: Int,
    ): FloatArray {
        val inputs = HashMap<String, OnnxTensor>(7)
        try {
            inputs["noisy_latent"] = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(noisyLatent), longArrayOf(1, 144, latentLen.toLong())
            )
            inputs["text_emb"] = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(textEmb), longArrayOf(1, 256, textLen.toLong())
            )
            inputs["style_ttl"] = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(styleTtl), longArrayOf(1, 50, 256)
            )
            inputs["latent_mask"] = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(latentMask), longArrayOf(1, 1, latentLen.toLong())
            )
            inputs["text_mask"] = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(textMask), longArrayOf(1, 1, textLen.toLong())
            )
            inputs["current_step"] = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(floatArrayOf(currentStep.toFloat())), longArrayOf(1)
            )
            inputs["total_step"] = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(floatArrayOf(totalStep.toFloat())), longArrayOf(1)
            )

            session.run(inputs).use { result ->
                val tensor = result.get("denoised_latent").get() as OnnxTensor
                val flat = FloatArray(144 * latentLen)
                tensor.floatBuffer.get(flat)
                return flat
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    override fun close() {
        session.close()
    }
}
