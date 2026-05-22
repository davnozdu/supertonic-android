package com.brahmadeo.supertonic.tts.tflite

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Port of helper.rs::sample_noisy_latent and length_to_mask, specialised for
 * the TFLite-hybrid pipeline where latent shape is fixed at
 * [1, LATENT_DIM, FIXED_LATENT_LEN] = [1, 144, 320].
 *
 * Constants matched against tts.json: ae.base_chunk_size=512,
 * ae.sample_rate=44100, ttl.chunk_compress_factor=6, ttl.latent_dim=24.
 * Resulting chunk_size = 512 * 6 = 3072, latent_dim_val = 24 * 6 = 144.
 */
object LatentSampler {
    private const val BASE_CHUNK = 512
    private const val CHUNK_COMPRESS = 6
    private const val LATENT_DIM = 24
    private const val SAMPLE_RATE = 44100
    const val CHUNK_SIZE = BASE_CHUNK * CHUNK_COMPRESS                   // 3072
    const val LATENT_DIM_VAL = LATENT_DIM * CHUNK_COMPRESS               // 144
    const val FIXED_LATENT_LEN = 320

    /**
     * Build the initial noisy latent + latent_mask sized exactly to the
     * actual content (no fixed 320 padding). The downstream ORT VE and
     * ORT vocoder accept dynamic latent_length, so we pay compute only
     * for the real audio length — closely matching the Rust pipeline.
     */
    fun sample(durationSec: Float, seed: Long? = null): Sample {
        val wavLen = (durationSec * SAMPLE_RATE).toInt().coerceAtLeast(0)
        val latentLen = ((wavLen + CHUNK_SIZE - 1) / CHUNK_SIZE).coerceAtLeast(1)
        val rng = if (seed != null) Random(seed) else Random.Default
        val noisy = FloatArray(LATENT_DIM_VAL * latentLen) {
            gaussian(rng, std = 0.667f)
        }
        // For an exactly-sized latent the mask is all ones (no padding tail).
        val mask = FloatArray(latentLen) { 1f }
        return Sample(noisy, mask, latentLen)
    }

    /**
     * Box-Muller transform: standard-normal * std.
     */
    private fun gaussian(rng: Random, std: Float): Float {
        var u1 = rng.nextFloat()
        if (u1 < 1e-9f) u1 = 1e-9f
        val u2 = rng.nextFloat()
        val mag = sqrt(-2.0 * ln(u1.toDouble())).toFloat()
        return (mag * kotlin.math.cos(2.0 * Math.PI * u2)).toFloat() * std
    }

    data class Sample(val noisyLatent: FloatArray, val latentMask: FloatArray, val latentLen: Int)
}
