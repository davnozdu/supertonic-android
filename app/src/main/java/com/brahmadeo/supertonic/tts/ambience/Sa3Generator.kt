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
class Sa3Generator(private val dir: File, private val threads: Int = 4, private val npuDit: NpuDit? = null,
                   /** The DiT on the CPU: sound effects (dit_w8a8) or music (dit_music_w8a8). */
                   private val ditFile: String = "dit_w8a8.tflite",
                   /** The DiT's rung lengths (the small and the medium models differ). */
                   private val ditRungs: IntArray = SMALL_RUNGS,
                   /** SAME-S (dec_w8a8) or SAME-L (dec_l_w8a8): the same latents. */
                   private val decFile: String = "dec_w8a8.tflite",
                   /** Keep the three graphs open for the whole book (the default): the encoder (563 MB) and the
                    * decoder (89 MB) then stop being reloaded for every clip, which took ~4 s of the 8 per clip.
                    * Costs about +650 MB of RSS while the book is being prepared; freed with [close]. */
                   private val keep: Boolean = true) : AutoCloseable {
    /** DiT on the NPU (ORT QNN): a static-length all-fp16-safe ONNX graph, used when the clip has exactly [length]
     * latents. Everything that depends only on t and seconds (sin/cos features with arguments up to ~6e4, the
     * global adaLN conditioning) runs in [pre], an fp32 graph on the ORT CPU; its outputs feed [session] by name. */
    class NpuDit(val sessions: List<ai.onnxruntime.OrtSession>, val pre: ai.onnxruntime.OrtSession, val length: Int) : AutoCloseable {
        constructor(session: ai.onnxruntime.OrtSession, pre: ai.onnxruntime.OrtSession, length: Int) : this(listOf(session), pre, length)
        private class Cond(val data: FloatArray, val shape: LongArray)
        private val conditioning = HashMap<Pair<Float, Float>, Map<String, Cond>>()

        private fun conditioning(t: Float, seconds: Float): Map<String, Cond> = conditioning.getOrPut(t to seconds) {
            val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            val scalars = mapOf("t" to ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(floatArrayOf(t)), longArrayOf(1)),
                "seconds_total" to ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(floatArrayOf(seconds)), longArrayOf(1)))
            try {
                pre.run(scalars).use { r -> r.associate { (name, value) ->
                    val o = value as ai.onnxruntime.OnnxTensor
                    name to Cond(FloatArray(o.info.shape.fold(1L) { a, b -> a * b }.toInt()).also { o.floatBuffer.get(it) }, o.info.shape)
                } }
            } finally { scalars.values.forEach { it.close() } }
        }

        fun velocity(x: FloatArray, t: Float, hidden: FloatArray, mask: FloatArray, seconds: Float): FloatArray {
            val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            fun tensor(data: FloatArray, vararg shape: Long) = ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(data), shape)
            val inputs = mapOf("x" to tensor(x, 1, LATENT_CH.toLong(), length.toLong()),
                "t5_hidden" to tensor(hidden, 1, COND_TOKENS.toLong(), COND_DIM.toLong()), "t5_mask" to tensor(mask, 1, COND_TOKENS.toLong()),
                "local_add_cond" to tensor(FloatArray(257 * length), 1, 257, length.toLong())) +
                conditioning(t, seconds).mapValues { (_, c) -> tensor(c.data, *c.shape) }
            try {
                return com.brahmadeo.supertonic.tts.utils.Npu.exclusive {
                    // A model cut into parts (the medium one): each part takes the inputs and the earlier parts'
                    // tensors it names; the last part's first output is the velocity.
                    val results = ArrayList<ai.onnxruntime.OrtSession.Result>()
                    try {
                        val values = HashMap<String, ai.onnxruntime.OnnxTensorLike>(inputs)
                        for (part in sessions) {
                            val r = part.run(part.inputNames.associateWith { values.getValue(it) }); results.add(r)
                            r.forEach { (name, value) -> values[name] = value as ai.onnxruntime.OnnxTensor }
                        }
                        val o = results.last()[0] as ai.onnxruntime.OnnxTensor
                        FloatArray(LATENT_CH * length).also { o.floatBuffer.get(it) }
                    } finally { results.forEach { it.close() } }
                }
            } finally { inputs.values.forEach { it.close() } }
        }
        override fun close() { sessions.forEach { it.close() }; pre.close() }
    }

    data class Stage(val name: String, val wallMs: Long, val cpuMs: Long)
    class Result(val pcm: FloatArray, val stages: List<Stage>) // interleaved stereo, 44.1 kHz

    /** One per generator (~40 MB for its 256 k pieces), freed with it after the book's sounds. */
    private val tokenizer by lazy { Sa3Tokenizer(File(dir, "tokenizer.model")) }

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

    /** Resident graphs ([keep]): one object per file, all of its rung signatures inside it. */
    private val resident = HashMap<String, CompiledModel>()

    private fun <T> withModel(name: String, cache: Boolean, block: (CompiledModel) -> T): T {
        if (!keep) return model(name, cache).use(block)
        val m = resident.getOrPut(name) { model(name, cache) }
        return block(m)
    }

    /** Frees the resident graphs; the NPU session is closed by its owner (AmbienceBook). */
    override fun close() {
        resident.values.forEach { runCatching { it.close() } }
        resident.clear()
    }

    fun encodeText(prompt: String): Pair<FloatArray, FloatArray> {
        val tokens = tokenizer.encode(prompt).take(COND_TOKENS)
        val ids = IntArray(COND_TOKENS); val mask = IntArray(COND_TOKENS)
        tokens.forEachIndexed { i, id -> ids[i] = id; mask[i] = 1 }
        return withModel("encoder_fp16.tflite", cache = false) { m ->
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
        npuDit?.takeIf { it.length == length }?.let { dit -> return pingpong(length, seed, cancelled) { x, t -> dit.velocity(x, t, hidden, mask, seconds) } }
        val rung = ditRungs.first { it >= length }
        val sig = "s$rung"
        return withModel(ditFile, cache = true) { m ->
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

    /** Rectified-flow pingpong over a velocity function on unpadded [256, length] latents. */
    private fun pingpong(length: Int, seed: Long, cancelled: () -> Boolean, velocity: (FloatArray, Float) -> FloatArray): FloatArray {
        val sigmas = schedule(); val rng = Random(seed)
        var x = FloatArray(LATENT_CH * length) { rng.nextGaussian().toFloat() }
        for (i in 0 until STEPS) {
            if (cancelled()) throw InterruptedException("cancelled")
            val tc = sigmas[i]; val tn = sigmas[i + 1]
            val v = velocity(x, tc)
            val renoise = i < STEPS - 1 && tn > 0f
            x = FloatArray(x.size) { k -> val d = x[k] - tc * v[k]; if (renoise) (1 - tn) * d + tn * rng.nextGaussian().toFloat() else d }
        }
        return x
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
        withModel(decFile, cache = true) { m ->
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
        const val COND_TOKENS = 256
        const val COND_DIM = 768
        const val LATENT_CH = 256
        private const val MEM = 64
        private const val TRIM = 16
        private const val STEPS = 8
        val SMALL_RUNGS = intArrayOf(8, 48, 96, 192, 416, 704, 1056, 1292)
        val MEDIUM_RUNGS = intArrayOf(8, 48, 96, 192, 416, 704, 1056, 1496, 2040, 2824, 3536, 4096)
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
