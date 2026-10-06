package com.brahmadeo.supertonic.tts.tera

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.brahmadeo.supertonic.tts.utils.Npu
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Hybrid Tera sampler (kit: tools/tera_npu/build_step.py): one denoising step as a graph that reads the
 * pinned sampler file's weights. Steps before [npuFrom] run on the CPU — on SM8850 / QNN 2.42 the HTP
 * returns NaN there (a compiler defect; the same values are tiny on the CPU) — later steps run on the NPU in
 * fixed frame/text buckets. Padding is exact (latent_mask, last valid frame for the edge pads); a step that
 * comes back non-finite is redone on the CPU. */
internal class TeraNpuSampler(private val ctx: Context, private val models: File, threads: Int) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val manifest = JSONObject(ctx.assets.open("tera_npu/kit.json").bufferedReader().use { it.readText() })
    private val steps = manifest.getInt("steps")
    private val npuFrom = manifest.getInt("npuFromStep")
    private val frameBuckets = manifest.getJSONArray("frameBuckets").let { a -> IntArray(a.length()) { a.getInt(it) } }
    private val textBuckets = manifest.getJSONArray("textBuckets").let { a -> IntArray(a.length()) { a.getInt(it) } }
    private val timeSize = 512
    private val time: FloatArray = ctx.assets.open("tera_npu/time.bin").use { it.readBytes() }.let { b ->
        FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) } }
    private val cpu: OrtSession
    private val npu = HashMap<Long, OrtSession>()
    private val sessions = mutableListOf<OrtSession>()
    var npuSteps = 0L; private set
    var fallbackSteps = 0L; private set

    init {
        require(File(models, manifest.getString("model")).length() == manifest.getLong("modelSize")) { "Tera NPU kit does not match the sampler" }
        require(time.size == steps * 4 * timeSize)
        val step = install()
        try {
            cpu = OrtSession.SessionOptions().use { o ->
                o.setIntraOpNumThreads(threads)
                o.addConfigEntry("session.intra_op.allow_spinning", "0")
                o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                env.createSession(step.path, o)
            }.also { sessions += it }
            val keys = mutableListOf<Long>(); val graphs = mutableListOf<Pair<File, Map<String, Long>>>()
            for (n in frameBuckets) for (l in textBuckets) {
                keys += key(n, l); graphs += step to mapOf("batch" to 1L, "generated_latent_length" to n.toLong(), "text_length" to l.toLong())
            }
            Npu.sharedSessions(ctx, env, graphs, "tera-step").forEachIndexed { i, s -> npu[keys[i]] = s; sessions += s }
        } catch (t: Throwable) { close(); throw t }
    }

    private fun key(frames: Int, text: Int) = frames.toLong() shl 32 or text.toLong()

    /** The step graph must sit next to the sampler: its external data points at it by relative name. */
    private fun install(): File {
        val target = File(models, "npukit-tera-step.onnx")
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L).toString()
        val marker = File(models, "npukit-tera-step.version")
        if (target.isFile && runCatching { marker.readText() }.getOrNull() == version) return target
        val tmp = File(models, "npukit-tera-step.onnx.tmp")
        ctx.assets.open("tera_npu/step.onnx").use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(tmp.renameTo(target)) { "Tera NPU kit install failed" }
        marker.writeText(version)
        return target
    }

    private fun tensor(data: FloatArray, vararg shape: Long) = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    private fun runStep(session: OrtSession, step: Int, x: FloatArray, n: Int, emb: FloatArray, l: Int, style: FloatArray,
                        latentMask: FloatArray, textMask: FloatArray, lastSel: FloatArray): FloatArray {
        val inputs = HashMap<String, OnnxTensor>()
        try {
            inputs["noisy_latent"] = tensor(x, 1, 144, n.toLong())
            inputs["text_emb"] = tensor(emb, 1, 256, l.toLong())
            inputs["style_ttl"] = tensor(style, 1, 50, 256)
            inputs["latent_mask"] = tensor(latentMask, 1, 1, n.toLong())
            inputs["text_mask"] = tensor(textMask, 1, 1, l.toLong())
            inputs["guidance"] = tensor(floatArrayOf(3f), 1)
            inputs["last_sel"] = tensor(lastSel, 1, 1, n.toLong())
            for (i in 0 until 4) inputs["t$i"] = tensor(time.copyOfRange((step * 4 + i) * timeSize, (step * 4 + i + 1) * timeSize), 1, timeSize.toLong(), 1)
            session.run(inputs).use { r ->
                val t = r[0] as OnnxTensor
                return FloatArray(144 * n).also { t.floatBuffer.get(it) }
            }
        } finally { inputs.values.forEach { it.close() } }
    }

    /** All denoising steps for one phrase; same result as the 8-step Loop up to FP16 on the NPU steps. */
    fun sample(noise: FloatArray, frames: Int, emb: FloatArray, textLen: Int, style: FloatArray): FloatArray {
        val ones = FloatArray(maxOf(frames, textLen)) { 1f }
        val latentMask = ones.copyOf(frames); val textMask = ones.copyOf(textLen)
        val lastSel = FloatArray(frames).also { it[frames - 1] = 1f }
        var x = noise
        fun cpuStep(s: Int) = runStep(cpu, s, x, frames, emb, textLen, style, latentMask, textMask, lastSel)
        for (s in 0 until minOf(npuFrom, steps)) x = cpuStep(s)
        val n = frameBuckets.firstOrNull { it >= frames }; val l = textBuckets.firstOrNull { it >= textLen }
        val session = if (n != null && l != null) npu[key(n, l)] else null
        if (session == null) { for (s in npuFrom until steps) x = cpuStep(s); return x }
        // Padded inputs for the bucket: zeros beyond the phrase, masks select the valid part.
        val pm = FloatArray(n).also { java.util.Arrays.fill(it, 0, frames, 1f) }
        val pt = FloatArray(l).also { java.util.Arrays.fill(it, 0, textLen, 1f) }
        val ps = FloatArray(n).also { it[frames - 1] = 1f }
        val pe = FloatArray(256 * l).also { for (c in 0 until 256) System.arraycopy(emb, c * textLen, it, c * l, textLen) }
        for (s in npuFrom until steps) {
            val px = FloatArray(144 * n).also { for (c in 0 until 144) System.arraycopy(x, c * frames, it, c * n, frames) }
            val y = runCatching { runStep(session, s, px, n, pe, l, style, pm, pt, ps) }.getOrNull()
            x = if (y != null && y.all { it.isFinite() }) {
                npuSteps++
                FloatArray(144 * frames).also { for (c in 0 until 144) System.arraycopy(y, c * n, it, c * frames, frames) }
            } else { fallbackSteps++; cpuStep(s) }
        }
        return x
    }

    override fun close() {
        sessions.forEach { runCatching { it.close() } }; sessions.clear(); npu.clear()
        Log.i("TeraTTS", "NPU sampler closed npuSteps=$npuSteps fallbackSteps=$fallbackSteps")
    }
}

/** Probe access from the diagnostics package (TeraNpuSampler is internal to the tera package's module). */
object TeraNpuSamplerProbe {
    fun create(ctx: Context, models: File): AutoCloseable = TeraNpuSampler(ctx, models, 2)
    fun sample(s: AutoCloseable, noise: FloatArray, frames: Int, emb: FloatArray, textLen: Int, style: FloatArray) =
        (s as TeraNpuSampler).sample(noise, frames, emb, textLen, style)
    fun counters(s: AutoCloseable) = (s as TeraNpuSampler).let { "npuSteps=${it.npuSteps} fallbackSteps=${it.fallbackSteps}" }
}
