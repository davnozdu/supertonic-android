package com.brahmadeo.supertonic.tts.tflite

import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thin wrappers around the author's INT4 .tflite models from
 * Reza2kn/supertonic-3-litert. Shapes here are FIXED at the values baked
 * into the .tflite graphs (text/latent length = 320, vocoder output = 983040).
 * The pipeline still needs adapting to those fixed shapes; this is just the
 * runtime surface.
 */

private const val FIXED_TEXT_LEN = 320
private const val LATENT_DIM = 144
private const val FIXED_LATENT_LEN = 320
private const val VOC_OUTPUT_LEN = 983040

private fun dirBuffer(byteSize: Int): ByteBuffer =
    ByteBuffer.allocateDirect(byteSize).order(ByteOrder.nativeOrder())

class TFLiteDurationPredictor(modelFile: File) : AutoCloseable {
    private val interp = Interpreter(modelFile, Interpreter.Options())

    /**
     * @param textIds  size FIXED_TEXT_LEN, int64
     * @param styleDp  size 8*16 = 128 floats
     * @param textMask size FIXED_TEXT_LEN floats, 1.0 for real tokens, 0.0 for padding
     * @return output scalar (shape [1])
     */
    fun run(textIds: LongArray, styleDp: FloatArray, textMask: FloatArray): FloatArray {
        require(textIds.size == FIXED_TEXT_LEN) { "textIds must be $FIXED_TEXT_LEN, got ${textIds.size}" }
        require(styleDp.size == 8 * 16) { "styleDp must be 128, got ${styleDp.size}" }
        require(textMask.size == FIXED_TEXT_LEN) { "textMask must be $FIXED_TEXT_LEN, got ${textMask.size}" }

        val inIds = dirBuffer(FIXED_TEXT_LEN * 8).apply {
            asLongBuffer().put(textIds); rewind()
        }
        val inStyle = dirBuffer(8 * 16 * 4).apply {
            asFloatBuffer().put(styleDp); rewind()
        }
        val inMask = dirBuffer(FIXED_TEXT_LEN * 4).apply {
            asFloatBuffer().put(textMask); rewind()
        }

        val output = dirBuffer(1 * 4)
        interp.runForMultipleInputsOutputs(
            arrayOf<Any>(inIds, inStyle, inMask),
            mapOf(0 to output),
        )

        val out = FloatArray(1)
        output.rewind()
        output.asFloatBuffer().get(out)
        return out
    }

    override fun close() = interp.close()
}

class TFLiteTextEncoder(modelFile: File) : AutoCloseable {
    private val interp = Interpreter(modelFile, Interpreter.Options())

    /**
     * @param styleTtl size 50*256 = 12800 floats
     * @return float buffer of shape [1, 256, FIXED_TEXT_LEN], flattened row-major
     */
    fun run(textIds: LongArray, styleTtl: FloatArray, textMask: FloatArray): FloatArray {
        require(textIds.size == FIXED_TEXT_LEN)
        require(styleTtl.size == 50 * 256)
        require(textMask.size == FIXED_TEXT_LEN)

        val inIds = dirBuffer(FIXED_TEXT_LEN * 8).apply {
            asLongBuffer().put(textIds); rewind()
        }
        val inStyle = dirBuffer(50 * 256 * 4).apply {
            asFloatBuffer().put(styleTtl); rewind()
        }
        val inMask = dirBuffer(FIXED_TEXT_LEN * 4).apply {
            asFloatBuffer().put(textMask); rewind()
        }

        val outSize = 1 * 256 * FIXED_TEXT_LEN
        val outBuf = dirBuffer(outSize * 4)
        interp.runForMultipleInputsOutputs(
            arrayOf<Any>(inIds, inStyle, inMask),
            mapOf(0 to outBuf),
        )

        val out = FloatArray(outSize)
        outBuf.rewind()
        outBuf.asFloatBuffer().get(out)
        return out
    }

    override fun close() = interp.close()
}

class TFLiteVocoder(modelFile: File) : AutoCloseable {
    private val interp = Interpreter(modelFile, Interpreter.Options())

    /**
     * @param latent size 1*144*FIXED_LATENT_LEN = 46080 floats
     * @return PCM samples of fixed length VOC_OUTPUT_LEN
     */
    fun run(latent: FloatArray): FloatArray {
        require(latent.size == LATENT_DIM * FIXED_LATENT_LEN) {
            "latent must be ${LATENT_DIM * FIXED_LATENT_LEN}, got ${latent.size}"
        }

        val inBuf = dirBuffer(latent.size * 4).apply {
            asFloatBuffer().put(latent); rewind()
        }
        val outBuf = dirBuffer(VOC_OUTPUT_LEN * 4)
        interp.runForMultipleInputsOutputs(
            arrayOf<Any>(inBuf),
            mapOf(0 to outBuf),
        )

        val out = FloatArray(VOC_OUTPUT_LEN)
        outBuf.rewind()
        outBuf.asFloatBuffer().get(out)
        return out
    }

    override fun close() = interp.close()
}
