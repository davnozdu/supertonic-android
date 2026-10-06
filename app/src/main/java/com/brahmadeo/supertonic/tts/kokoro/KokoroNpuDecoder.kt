package com.brahmadeo.supertonic.tts.kokoro

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
import java.nio.LongBuffer
import kotlin.math.sqrt

/** Kokoro full-precision generator on the Snapdragon NPU (kit: tools/kokoro_npu/build_kit.py).
 *
 * The generator's AdaIN InstanceNorms need statistics over the whole phrase, so every resblock runs as
 * small NPU graphs between two norms (AdaIN apply -> Snake -> Conv) in fixed chunks with a halo, and the
 * per-channel mean/variance is computed here over the full phrase. Text front, upsampling and the iSTFT head
 * stay on the CPU. Kit graphs reference the weights inside the pinned model file, nothing is copied. */
internal class KokoroNpuDecoder(private val ctx: Context, private val root: File, val modelFile: String, threads: Int,
                                private val onNpu: Boolean = true) : AutoCloseable {
    class Pre(val xin: FloatArray, val xinLen: Int, val nc0: FloatArray, val len0: Int, val nc1: FloatArray, val len1: Int,
              val p: FloatArray, val q: FloatArray)

    private class Seg(val stage: Int, val channels: Int, val coef: Int, val session: OrtSession)
    private class Bufs(c: Int, val w: Int) {
        val x = direct(c * w); val r = direct(c * w); val y = direct(c * w); val m = direct(w); val a = direct(c); val b = direct(c)
    }

    companion object {
        private const val TAG = "KokoroNpu"
        const val CHUNK_FRAMES = 32
        private val PER_FRAME = intArrayOf(20, 120)
        private val CHANNELS = intArrayOf(256, 128)
        /** Longer phrases would need several 50+ MB tensors per stage; they stay on the CPU path. */
        const val MAX_FRAMES = 520
        private fun direct(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fun kitName(modelFile: String) = modelFile.removeSuffix(".onnx")
    }

    private val env = OrtEnvironment.getEnvironment()
    private val base = kitName(modelFile)
    private val manifest = JSONObject(ctx.assets.open("kokoro_npu/$base/kit.json").bufferedReader().use { it.readText() })
    private val eps = manifest.getDouble("eps")
    private val halo = manifest.getInt("halo")
    private val chunk = IntArray(2) { CHUNK_FRAMES * PER_FRAME[it] }
    private val stages = List(2) { s -> manifest.getJSONArray("stages").getJSONObject(s).getJSONArray("blocks").let { a -> List(a.length()) { a.getString(it) } } }
    private val sessions = mutableListOf<OrtSession>()
    private val pre: OrtSession; private val up0: OrtSession; private val up1: OrtSession; private val post: OrtSession
    private val segs = HashMap<String, Seg>()
    private val bufs = Array(2) { Bufs(CHANNELS[it], chunk[it] + 2 * halo) }
    var calls = 0L; private set

    init {
        val model = File(root, modelFile)
        require(model.length() == manifest.getLong("modelSize")) { "Kokoro NPU kit does not match $modelFile" }
        installKit()
        try {
            fun cpu(name: String) = OrtSession.SessionOptions().use { o ->
                o.setIntraOpNumThreads(threads)
                o.addConfigEntry("session.intra_op.allow_spinning", "0")
                o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                env.createSession(kitFile(name).path, o)
            }.also { sessions += it }
            pre = cpu("pre.onnx"); up0 = cpu("up0.onnx"); up1 = cpu("up1.onnx"); post = cpu("post.onnx")
            val list = manifest.getJSONArray("segments")
            for (i in 0 until list.length()) {
                val s = list.getJSONObject(i); val stage = s.getInt("stage"); val file = s.getString("file")
                val w = chunk[stage] + 2 * halo
                val session = if (onNpu) Npu.session(ctx, env, kitFile(file), mapOf("w" to w.toLong()), "kokoro-$base-${file.removeSuffix(".onnx")}-$w")
                              else OrtSession.SessionOptions().use { o -> o.setIntraOpNumThreads(threads); o.setSymbolicDimensionValue("w", w.toLong()); env.createSession(kitFile(file).path, o) }
                sessions += session
                segs[key(stage, s.getString("block"), s.getInt("dilation"), s.getInt("half"))] = Seg(stage, s.getInt("channels"), s.getInt("coef"), session)
            }
        } catch (t: Throwable) { close(); throw t }
    }

    private fun key(stage: Int, block: String, dilation: Int, half: Int) = "$stage/$block/$dilation/$half"
    private fun kitFile(name: String) = File(root, "npukit-$base-$name")

    /** Kit graphs must sit next to the model: their external data points at it by relative name. */
    private fun installKit() {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0L).toString()
        val marker = File(root, "npukit-$base.version")
        if (runCatching { marker.readText() }.getOrNull() == version) return
        for (name in ctx.assets.list("kokoro_npu/$base").orEmpty()) {
            val target = kitFile(name); val tmp = File(root, "${target.name}.tmp")
            ctx.assets.open("kokoro_npu/$base/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(target)) { "Kokoro NPU kit install failed" }
        }
        marker.writeText(version)
    }

    fun frames(pre: Pre) = pre.len0 / PER_FRAME[0]

    fun prepare(ids: LongArray, style: FloatArray, speed: Float): Pre {
        val inputs = mapOf("input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())),
            "style" to OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, 256)),
            "speed" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)))
        try {
            pre.run(inputs).use { r ->
                fun get(name: String): Pair<FloatArray, Int> { val t = r.get(name).get() as OnnxTensor; val shape = t.info.shape
                    return FloatArray(shape.fold(1L) { a, b -> a * b }.toInt()).also { t.floatBuffer.get(it) } to shape.last().toInt() }
                val (xin, xl) = get("xin"); val (nc0, l0) = get("nc0"); val (nc1, l1) = get("nc1")
                return Pre(xin, xl, nc0, l0, nc1, l1, get("P").first, get("Q").first)
            }
        } finally { inputs.values.forEach { it.close() } }
    }

    private fun cpuRun(session: OrtSession, x: FloatArray, channels: Int, len: Int, output: String): Pair<FloatArray, Int> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, channels.toLong(), len.toLong())).use { input ->
            session.run(mapOf("x" to input)).use { r ->
                val t = r.get(output).get() as OnnxTensor
                return FloatArray(t.info.shape.fold(1L) { a, b -> a * b }.toInt()).also { t.floatBuffer.get(it) } to t.info.shape.last().toInt()
            }
        }
    }

    /** Waveform at 24 kHz from the prepared front; identical maths to the original generator. */
    fun generate(p: Pre): FloatArray {
        val (u0, l0) = cpuRun(up0, p.xin, 512, p.xinLen, "u0")
        check(l0 == p.len0) { "Kokoro NPU stage 0 length $l0 != ${p.len0}" }
        val s0 = Stage(0, l0, p)
        s0.add(u0, s0.resblock(stages[0][0], p.nc0))
        val xs0 = s0.average(u0)
        val (u1, l1) = cpuRun(up1, xs0, 256, l0, "u1")
        check(l1 == p.len1) { "Kokoro NPU stage 1 length $l1 != ${p.len1}" }
        val s1 = Stage(1, l1, p)
        s1.add(u1, s1.resblock(stages[1][0], p.nc1))
        val xs1 = s1.average(u1)
        return cpuRun(post, xs1, 128, l1, "waveform").first
    }

    private inner class Stage(val stage: Int, val len: Int, val pre: Pre) {
        val c = CHANNELS[stage]
        private val tmp = FloatArray(c * len); private val pa = FloatArray(c * len); private val pb = FloatArray(c * len)

        fun add(target: FloatArray, source: FloatArray) { for (i in target.indices) target[i] += source[i] }

        /** Mean of the stage's parallel resblocks on the same input (MRF), as in iSTFTNet. */
        fun average(x: FloatArray): FloatArray {
            val acc = FloatArray(c * len)
            for (block in stages[stage].drop(1)) add(acc, resblock(block, x))
            for (i in acc.indices) acc[i] /= 3f
            return acc
        }

        /** Returns a buffer owned by this stage; valid until the next resblock call. */
        fun resblock(block: String, input: FloatArray): FloatArray {
            var cur = input; var next = pa
            for (j in 0 until 3) {
                segment(segs.getValue(key(stage, block, j, 1)), cur, null, tmp)
                segment(segs.getValue(key(stage, block, j, 2)), tmp, cur, next)
                cur = next; next = if (next === pa) pb else pa
            }
            return cur
        }

        private fun segment(seg: Seg, x: FloatArray, residual: FloatArray?, out: FloatArray) {
            val b = bufs[stage]; val w = b.w; val ch = chunk[stage]
            // AdaIN with whole-phrase statistics: A = P / sigma, B = Q - P * mu / sigma.
            b.a.clear(); b.b.clear()
            for (k in 0 until c) {
                var sum = 0.0; var sq = 0.0; val o = k * len
                for (i in o until o + len) { val v = x[i].toDouble(); sum += v; sq += v * v }
                val mean = sum / len; val sigma = sqrt(maxOf(sq / len - mean * mean, 0.0) + eps)
                val pk = pre.p[seg.coef + k].toDouble(); val qk = pre.q[seg.coef + k].toDouble()
                b.a.put(k, (pk / sigma).toFloat()); b.b.put(k, (qk - pk * mean / sigma).toFloat())
            }
            val shape = longArrayOf(1, c.toLong(), w.toLong())
            // ORT wraps direct buffers from their current position: rewind before wrapping.
            listOf(b.a, b.b, b.x, b.m, b.r, b.y).forEach { it.clear() }
            val tA = OnnxTensor.createTensor(env, b.a, longArrayOf(1, c.toLong(), 1)); val tB = OnnxTensor.createTensor(env, b.b, longArrayOf(1, c.toLong(), 1))
            val tX = OnnxTensor.createTensor(env, b.x, shape); val tM = OnnxTensor.createTensor(env, b.m, longArrayOf(1, 1, w.toLong()))
            val tR = residual?.let { OnnxTensor.createTensor(env, b.r, shape) }; val tY = OnnxTensor.createTensor(env, b.y, shape)
            try {
                val inputs = HashMap<String, OnnxTensor>().apply { put("X", tX); put("A", tA); put("B", tB); put("M", tM); tR?.let { put("R", it) } }
                var start = 0
                while (start < len) {
                    val lo = start - halo; val s0 = maxOf(lo, 0); val s1 = minOf(lo + w, len)
                    fill(b.x, x, s0 - lo, s1 - s0, s0); residual?.let { fill(b.r, it, s0 - lo, s1 - s0, s0) }
                    for (i in 0 until w) b.m.put(i, if (i >= s0 - lo && i < s1 - lo) 1f else 0f)
                    seg.session.run(inputs, mapOf("Y" to tY)).close(); calls++
                    val n = minOf(ch, len - start)
                    for (k in 0 until c) { b.y.position(k * w + halo); b.y.get(out, k * len + start, n) }
                    b.y.clear()
                    start += ch
                }
            } finally { listOfNotNull(tA, tB, tX, tM, tR, tY).forEach { it.close() } }
        }

        /** Copies [srcStart, srcStart+count) of every channel into the chunk at dstOffset; zeros elsewhere. */
        private fun fill(dst: FloatBuffer, src: FloatArray, dstOffset: Int, count: Int, srcStart: Int) {
            val w = bufs[stage].w
            for (k in 0 until c) {
                val row = k * w
                for (i in 0 until dstOffset) dst.put(row + i, 0f)
                dst.position(row + dstOffset); dst.put(src, k * len + srcStart, count)
                for (i in dstOffset + count until w) dst.put(row + i, 0f)
            }
            dst.clear()
        }
    }

    override fun close() {
        sessions.forEach { runCatching { it.close() } }; sessions.clear(); segs.clear()
        Log.i(TAG, "Kokoro NPU decoder closed")
    }
}
