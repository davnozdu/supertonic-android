package com.brahmadeo.supertonic.tts.ambience

import android.os.Process
import android.os.SystemClock
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt

/** Stable Audio 3 Small SFX text-to-audio on the LiteRT CPU (XNNPACK), a port of Stability's sa3_tflite
 * (cfg 1, 8-step rectified-flow pingpong). The three graphs load one after another and close straight away,
 * so only one of them is resident at a time. The DiT and the decoder are "rung" files: one signature per fixed
 * length sharing weights; the XNNPACK weight cache next to each file keeps that sharing (flat RAM). */
class Sa3Generator(private val dir: File, private val threads: Int = 4) {
    data class Stage(val name: String, val wallMs: Long, val cpuMs: Long)
    class Result(val pcm: FloatArray, val stages: List<Stage>) // interleaved stereo, 44.1 kHz

    fun generate(prompt: String, seconds: Float, seed: Long, cancelled: () -> Boolean = { false },
                 onStage: (Stage) -> Unit = {}): Result {
        require(seconds in 1f..MAX_SECONDS) { "Длительность 1…${MAX_SECONDS.toInt()} с" }
        val stages = ArrayList<Stage>()
        fun <T> stage(name: String, work: () -> T): T {
            val wall = SystemClock.elapsedRealtime(); val cpu = Process.getElapsedCpuTime()
            val out = work()
            Stage(name, SystemClock.elapsedRealtime() - wall, Process.getElapsedCpuTime() - cpu).also { stages.add(it); onStage(it) }
            if (cancelled()) throw InterruptedException("cancelled")
            return out
        }
        val latents = maxOf(1, ceil(seconds * SAMPLE_RATE / SAMPLES_PER_LATENT).toInt())
        val (hidden, mask) = stage("t5") { encodeText(prompt) }
        val x = stage("dit") { sample(hidden, mask, latents, seconds, seed, cancelled) }
        val audio = stage("decode") { decode(x, latents) }
        val frames = minOf(audio.size / 2, (seconds * SAMPLE_RATE).roundToInt())
        return Result(audio.copyOf(frames * 2), stages)
    }

    private fun model(name: String, cache: Boolean) = CompiledModel.create(File(dir, name).path,
        CompiledModel.Options(Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(threads, null, if (cache) File(dir, "$name.xnnwc").path else null)
        })

    private fun encodeText(prompt: String): Pair<FloatArray, FloatArray> {
        val tokens = Sa3Tokenizer(File(dir, "tokenizer.model")).encode(prompt).take(COND_TOKENS)
        val ids = IntArray(COND_TOKENS); val mask = IntArray(COND_TOKENS)
        tokens.forEachIndexed { i, id -> ids[i] = id; mask[i] = 1 }
        return model("encoder_fp16.tflite", cache = false).use { m ->
            val sig = "serving_default"
            val inputs = mapOf("args_0" to m.createInputBuffer("args_0", sig).apply { writeInt(ids) },
                "args_1" to m.createInputBuffer("args_1", sig).apply { writeInt(mask) })
            val out = m.createOutputBuffer("output_0", sig)
            try {
                m.run(inputs, mapOf("output_0" to out), sig)
                out.readFloat().copyOf(COND_TOKENS * COND_DIM) to FloatArray(COND_TOKENS) { mask[it].toFloat() }
            } finally { (inputs.values + out).forEach(TensorBuffer::close) }
        }
    }

    private fun sample(hidden: FloatArray, mask: FloatArray, length: Int, seconds: Float, seed: Long, cancelled: () -> Boolean): FloatArray {
        val rung = DIT_RUNGS.first { it >= length }
        val sig = "s$rung"
        return model("dit_w8a8.tflite", cache = true).use { m ->
            val buffers = (0..6).associate { "args_$it" to m.createInputBuffer("args_$it", sig) }
            val out = m.createOutputBuffer("output_0", sig)
            val gIn = (0..1).map { m.createInputBuffer("args_$it", "gcond") }
            val gOut = m.createOutputBuffer("output_0", "gcond")
            try {
                buffers.getValue("args_2").writeFloat(hidden)
                buffers.getValue("args_3").writeFloat(mask)
                buffers.getValue("args_4").writeFloat(floatArrayOf(seconds))
                buffers.getValue("args_5").writeFloat(FloatArray(257 * rung))
                buffers.getValue("args_6").writeFloat(FloatArray(MEM + rung) { if (it >= MEM + length) -1e9f else 0f })
                val sigmas = schedule()
                val rng = Random(seed)
                val n = LATENT_CH * length
                var x = FloatArray(n) { rng.nextGaussian().toFloat() }
                val padded = FloatArray(LATENT_CH * rung)
                for (i in 0 until STEPS) {
                    if (cancelled()) throw InterruptedException("cancelled")
                    val tc = sigmas[i]; val tn = sigmas[i + 1]
                    gIn[0].writeFloat(floatArrayOf(seconds)); gIn[1].writeFloat(floatArrayOf(tc))
                    m.run(mapOf("args_0" to gIn[0], "args_1" to gIn[1]), mapOf("output_0" to gOut), "gcond")
                    buffers.getValue("args_1").writeFloat(gOut.readFloat())
                    for (c in 0 until LATENT_CH) System.arraycopy(x, c * length, padded, c * rung, length)
                    buffers.getValue("args_0").writeFloat(padded)
                    m.run(buffers, mapOf("output_0" to out), sig)
                    val v = out.readFloat()
                    val renoise = i < STEPS - 1 && tn > 0f
                    val next = FloatArray(n)
                    for (c in 0 until LATENT_CH) for (t in 0 until length) {
                        val k = c * length + t
                        val denoised = x[k] - tc * v[c * rung + t]
                        next[k] = if (renoise) (1 - tn) * denoised + tn * rng.nextGaussian().toFloat() else denoised
                    }
                    x = next
                }
                x
            } finally { (buffers.values + out + gIn + gOut).forEach(TensorBuffer::close) }
        }
    }

    /** SAME-S decode on rungs: one exact window when the length is a rung, else lossless overlapped tiles
     * (TRIM latents per side) on the rung that decodes the fewest latents in total. */
    private fun decode(x: FloatArray, length: Int): FloatArray {
        val even = if (length % 2 == 0) length else length + 1        // SAME-S needs an even length
        val lat = Array(LATENT_CH) { c -> FloatArray(even) { t -> x[c * length + minOf(t, length - 1)] } }
        val candidates = DEC_RUNGS.filter { it == even || (it < even && it - 2 * TRIM > 0) }
            .map { r -> Triple(windows(even, r) * r, windows(even, r), r) }
        val rung = candidates.minWithOrNull(compareBy<Triple<Int, Int, Int>> { it.first }.thenBy { it.second })?.third
            ?: DEC_RUNGS.first { it >= even }
        val out = FloatArray(even * SAMPLES_PER_LATENT * 2)
        model("dec_w8a8.tflite", cache = true).use { m ->
            val sig = "s$rung"
            val input = m.createInputBuffers(sig); val output = m.createOutputBuffers(sig)
            try {
                fun window(start: Int): FloatArray {
                    val chunk = FloatArray(LATENT_CH * rung)
                    for (c in 0 until LATENT_CH) for (t in 0 until rung) chunk[c * rung + t] = lat[c][minOf(start + t, even - 1)]
                    input[0].writeFloat(chunk); m.run(input, output, sig)
                    return output[0].readFloat() // [1, 2, rung*4096] planar
                }
                val starts = if (even <= rung) listOf(0) else
                    (0 until even - rung step rung - 2 * TRIM).toMutableList().also { if (it.lastOrNull() != even - rung) it.add(even - rung) }
                val span = rung * SAMPLES_PER_LATENT
                starts.forEachIndexed { k, st ->
                    val y = window(st)
                    val lo = if (k == 0) 0 else TRIM
                    val hi = if (st + rung >= even) minOf(rung, even - st) else rung - TRIM
                    for (s in lo * SAMPLES_PER_LATENT until hi * SAMPLES_PER_LATENT) {
                        val frame = st * SAMPLES_PER_LATENT + s
                        out[frame * 2] = y[s]; out[frame * 2 + 1] = y[span + s]
                    }
                }
            } finally { (input + output).forEach(TensorBuffer::close) }
        }
        return out.copyOf(length * SAMPLES_PER_LATENT * 2)
    }

    private fun windows(length: Int, rung: Int): Int {
        if (length <= rung) return 1
        val starts = (0 until length - rung step rung - 2 * TRIM).toMutableList()
        if (starts.lastOrNull() != length - rung) starts.add(length - rung)
        return starts.size
    }

    companion object {
        const val SAMPLE_RATE = 44100
        const val MAX_SECONDS = 120f
        private const val SAMPLES_PER_LATENT = 4096
        private const val COND_TOKENS = 256
        private const val COND_DIM = 768
        private const val LATENT_CH = 256
        private const val MEM = 64
        private const val TRIM = 16
        private const val STEPS = 8
        private val DIT_RUNGS = intArrayOf(8, 48, 96, 192, 416, 704, 1056, 1292)
        private val DEC_RUNGS = intArrayOf(2, 4, 8, 12, 16, 32, 64, 128, 256)

        /** LogSNR-shifted pingpong schedule from 1 to 0 (anchor −6.2, end 2.0), first point exactly 1. */
        fun schedule(): FloatArray = FloatArray(STEPS + 1) { i ->
            val t = 1f - i.toFloat() / STEPS
            when { i == 0 -> 1f; t <= 0f -> 0f; else -> (1.0 / (1.0 + exp(2.0 - t * (2.0 + 6.2)))).toFloat() }
        }

        /** 16-bit stereo WAV, peak-limited to [-1, 1]. */
        fun writeWav(file: File, pcm: FloatArray) {
            val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (v in pcm) data.putShort((v.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1); putShort(2); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 4); putShort(4); putShort(16)
                put("data".toByteArray()); putInt(data.capacity())
            }
            RandomAccessFile(file, "rw").use { it.setLength(0); it.write(header.array()); it.write(data.array()) }
        }
    }
}
