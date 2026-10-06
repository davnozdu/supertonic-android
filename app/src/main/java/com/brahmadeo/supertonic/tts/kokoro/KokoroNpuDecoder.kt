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
 * per-channel mean/variance is gathered here while chunk results are copied out. Both upsamplers and
 * conv_post also run on the NPU; only the text front (BERT/LSTM) and the iSTFT tail stay on the CPU.
 * All NPU graphs share one QNN context. Kit graphs reference the weights inside the pinned model file. */
internal class KokoroNpuDecoder(private val ctx: Context, private val root: File, val modelFile: String, threads: Int,
                                private val onNpu: Boolean = true) : AutoCloseable {
    class Pre(val xin: FloatArray, val xinLen: Int, val nc0: FloatArray, val len0: Int, val nc1: FloatArray, val len1: Int,
              val p: FloatArray, val q: FloatArray)

    /** Per-channel sum and sum of squares over the valid phrase length. */
    private class Stats(c: Int) { val sum = DoubleArray(c); val sq = DoubleArray(c) }

    private class Seg(val stage: Int, val coef: Int, val session: OrtSession)
    /** Fixed-shape chunk runner: input [cin, w] -> output [cout, scale * w]. */
    private class Op(val session: OrtSession, val cin: Int, val cout: Int, val scale: Int, val halo: Int, val core: Int) {
        val w = core + 2 * halo
        val x = direct(cin * w); val y = direct(cout * scale * w)
    }
    private class SegBufs(c: Int, val w: Int) {
        val x = direct(c * w); val r = direct(c * w); val y = direct(c * w); val m = direct(w); val a = direct(c); val b = direct(c)
    }

    companion object {
        private const val TAG = "KokoroNpu"
        const val CHUNK_FRAMES = 32
        private val PER_FRAME = intArrayOf(20, 120)
        private val CHANNELS = intArrayOf(256, 128)
        /** ~20 s; longer chunks would need several 50+ MB tensors per stage and stay on the CPU path. */
        const val MAX_FRAMES = 800
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
    private val pre: OrtSession; private val tail: OrtSession
    private val up0: Op; private val up1: Op; private val postConv: Op
    private val segs = HashMap<String, Seg>()
    private val segBufs = Array(2) { SegBufs(CHANNELS[it], chunk[it] + 2 * halo) }
    var calls = 0L; private set

    init {
        val model = File(root, modelFile)
        require(model.length() == manifest.getLong("modelSize")) { "Kokoro NPU kit does not match $modelFile" }
        installKit()
        try {
            // The CPU keeps only the text front and the iSTFT tail; two threads cost less CPU time than four.
            val cpuThreads = minOf(threads, 2)
            fun cpu(name: String, dims: Map<String, Long> = emptyMap()) = OrtSession.SessionOptions().use { o ->
                o.setIntraOpNumThreads(cpuThreads)
                o.addConfigEntry("session.intra_op.allow_spinning", "0")
                o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                dims.forEach { (k, v) -> o.setSymbolicDimensionValue(k, v) }
                env.createSession(kitFile(name).path, o)
            }.also { sessions += it }
            pre = cpu("pre.onnx"); tail = cpu("tail.onnx")
            val glue = manifest.getJSONObject("glue")
            val ops = listOf("nup0" to 2 * CHUNK_FRAMES, "nup1" to chunk[0], "npost" to chunk[1]).map { (name, core) ->
                val g = glue.getJSONObject(name); Triple(name, g, core) }
            val list = manifest.getJSONArray("segments")
            val graphs = mutableListOf<Pair<File, Map<String, Long>>>()
            for ((name, g, core) in ops) graphs += kitFile("$name.onnx") to mapOf("w" to (core + 2 * g.getInt("halo")).toLong())
            for (i in 0 until list.length()) {
                val s = list.getJSONObject(i)
                graphs += kitFile(s.getString("file")) to mapOf("w" to (chunk[s.getInt("stage")] + 2 * halo).toLong())
            }
            val npuSessions = if (onNpu) Npu.sharedSessions(ctx, env, graphs, "kokoro-$base")
                              else graphs.map { (file, dims) -> cpu(file.name.removePrefix("npukit-$base-"), dims) }
            if (onNpu) sessions += npuSessions
            val made = ops.mapIndexed { i, (_, g, core) -> Op(npuSessions[i], g.getInt("in"), g.getInt("out"), g.getInt("scale"), g.getInt("halo"), core) }
            up0 = made[0]; up1 = made[1]; postConv = made[2]
            for (i in 0 until list.length()) {
                val s = list.getJSONObject(i)
                segs[key(s.getInt("stage"), s.getString("block"), s.getInt("dilation"), s.getInt("half"))] =
                    Seg(s.getInt("stage"), s.getInt("coef"), npuSessions[ops.size + i])
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
        root.listFiles { f -> f.name.startsWith("npukit-$base-") }?.forEach { it.delete() }
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

    /** Waveform at 24 kHz from the prepared front; identical maths to the original generator. */
    fun generate(p: Pre): FloatArray {
        val l0 = p.len0; val l1 = p.len1
        check(l0 == p.xinLen * up0.scale && l1 == l0 * up1.scale + 1) { "Kokoro NPU lengths ${p.xinLen}/$l0/$l1" }
        val u0 = FloatArray(256 * l0); chunked(up0, p.xin, p.xinLen, u0, l0, 0)
        val s0 = Stage(0, l0, p)
        s0.add(u0, s0.resblock(stages[0][0], p.nc0, stats(p.nc0, 256, l0)))
        val xs0 = s0.average(u0)
        val u1 = FloatArray(128 * l1); chunked(up1, xs0, l0, u1, l1, 1)
        for (k in 0 until 128) u1[k * l1] = u1[k * l1 + 2]  // ReflectionPad1d((1, 0))
        val s1 = Stage(1, l1, p)
        s1.add(u1, s1.resblock(stages[1][0], p.nc1, stats(p.nc1, 128, l1)))
        val xs1 = s1.average(u1)
        val post = FloatArray(22 * l1); chunked(postConv, xs1, l1, post, l1, 0)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(post), longArrayOf(1, 22, l1.toLong())).use { input ->
            tail.run(mapOf("x" to input)).use { r ->
                val t = r.get("waveform").get() as OnnxTensor
                return FloatArray(t.info.shape.fold(1L) { a, b -> a * b }.toInt()).also { t.floatBuffer.get(it) }
            }
        }
    }

    private fun stats(x: FloatArray, c: Int, len: Int) = Stats(c).also { s ->
        for (k in 0 until c) { var sum = 0.0; var sq = 0.0; for (i in k * len until (k + 1) * len) { val v = x[i].toDouble(); sum += v; sq += v * v }; s.sum[k] = sum; s.sq[k] = sq }
    }

    /** Chunked local op with a halo: zeros outside the phrase reproduce the original zero padding. */
    private fun chunked(op: Op, x: FloatArray, len: Int, out: FloatArray, outLen: Int, shift: Int) {
        val w = op.w; val sw = op.scale * w
        op.x.clear(); op.y.clear()
        val tX = OnnxTensor.createTensor(env, op.x, longArrayOf(1, op.cin.toLong(), w.toLong()))
        val tY = OnnxTensor.createTensor(env, op.y, longArrayOf(1, op.cout.toLong(), sw.toLong()))
        try {
            val inputs = mapOf("X" to tX); val outputs = mapOf("Y" to tY)
            var start = 0
            while (start < len) {
                val lo = start - op.halo; val s0 = maxOf(lo, 0); val s1 = minOf(lo + w, len)
                fill(op.x, x, op.cin, len, w, s0 - lo, s1 - s0, s0)
                op.session.run(inputs, outputs).close(); calls++
                val n = minOf(op.core, len - start) * op.scale
                for (k in 0 until op.cout) { op.y.position(k * sw + op.scale * op.halo); op.y.get(out, k * outLen + shift + start * op.scale, n) }
                op.y.clear()
                start += op.core
            }
        } finally { tX.close(); tY.close() }
    }

    /** Copies [srcStart, srcStart+count) of every channel into the chunk at dstOffset; zeros elsewhere. */
    private fun fill(dst: FloatBuffer, src: FloatArray, c: Int, len: Int, w: Int, dstOffset: Int, count: Int, srcStart: Int) {
        for (k in 0 until c) {
            val row = k * w
            for (i in 0 until dstOffset) dst.put(row + i, 0f)
            dst.position(row + dstOffset); dst.put(src, k * len + srcStart, count)
            for (i in dstOffset + count until w) dst.put(row + i, 0f)
        }
        dst.clear()
    }

    private inner class Stage(val stage: Int, val len: Int, val pre: Pre) {
        val c = CHANNELS[stage]
        private val tmp = FloatArray(c * len); private val pa = FloatArray(c * len); private val pb = FloatArray(c * len)

        fun add(target: FloatArray, source: FloatArray) { for (i in target.indices) target[i] += source[i] }

        /** Mean of the stage's parallel resblocks on the same input (MRF), as in iSTFTNet. */
        fun average(x: FloatArray): FloatArray {
            val acc = FloatArray(c * len); val xs = stats(x, c, len)
            for (block in stages[stage].drop(1)) add(acc, resblock(block, x, xs))
            for (i in acc.indices) acc[i] /= 3f
            return acc
        }

        /** Returns a buffer owned by this stage; valid until the next resblock call. */
        fun resblock(block: String, input: FloatArray, inputStats: Stats): FloatArray {
            var cur = input; var curStats = inputStats; var next = pa
            for (j in 0 until 3) {
                val yStats = segment(segs.getValue(key(stage, block, j, 1)), cur, curStats, null, tmp)
                curStats = segment(segs.getValue(key(stage, block, j, 2)), tmp, yStats, cur, next)
                cur = next; next = if (next === pa) pb else pa
            }
            return cur
        }

        /** One AdaIN -> Snake -> Conv graph over the phrase; returns the statistics of its output. */
        private fun segment(seg: Seg, x: FloatArray, xStats: Stats, residual: FloatArray?, out: FloatArray): Stats {
            val b = segBufs[stage]; val w = b.w; val ch = chunk[stage]
            // AdaIN with whole-phrase statistics: A = P / sigma, B = Q - P * mu / sigma.
            for (k in 0 until c) {
                val mean = xStats.sum[k] / len; val sigma = sqrt(maxOf(xStats.sq[k] / len - mean * mean, 0.0) + eps)
                val pk = pre.p[seg.coef + k].toDouble(); val qk = pre.q[seg.coef + k].toDouble()
                b.a.put(k, (pk / sigma).toFloat()); b.b.put(k, (qk - pk * mean / sigma).toFloat())
            }
            // ORT wraps direct buffers from their current position: rewind before wrapping.
            listOf(b.a, b.b, b.x, b.m, b.r, b.y).forEach { it.clear() }
            val shape = longArrayOf(1, c.toLong(), w.toLong())
            val tA = OnnxTensor.createTensor(env, b.a, longArrayOf(1, c.toLong(), 1)); val tB = OnnxTensor.createTensor(env, b.b, longArrayOf(1, c.toLong(), 1))
            val tX = OnnxTensor.createTensor(env, b.x, shape); val tM = OnnxTensor.createTensor(env, b.m, longArrayOf(1, 1, w.toLong()))
            val tR = residual?.let { OnnxTensor.createTensor(env, b.r, shape) }; val tY = OnnxTensor.createTensor(env, b.y, shape)
            val outStats = Stats(c)
            try {
                val inputs = HashMap<String, OnnxTensor>().apply { put("X", tX); put("A", tA); put("B", tB); put("M", tM); tR?.let { put("R", it) } }
                val outputs = mapOf("Y" to tY)
                var maskFull = false
                var start = 0
                while (start < len) {
                    val lo = start - halo; val s0 = maxOf(lo, 0); val s1 = minOf(lo + w, len)
                    fill(b.x, x, c, len, w, s0 - lo, s1 - s0, s0); residual?.let { fill(b.r, it, c, len, w, s0 - lo, s1 - s0, s0) }
                    val full = s0 - lo == 0 && s1 - s0 == w
                    if (!full || !maskFull) { for (i in 0 until w) b.m.put(i, if (i >= s0 - lo && i < s1 - lo) 1f else 0f); maskFull = full }
                    seg.session.run(inputs, outputs).close(); calls++
                    val n = minOf(ch, len - start)
                    for (k in 0 until c) {
                        val o = k * len + start
                        b.y.position(k * w + halo); b.y.get(out, o, n)
                        var sum = 0.0; var sq = 0.0
                        for (i in o until o + n) { val v = out[i].toDouble(); sum += v; sq += v * v }
                        outStats.sum[k] += sum; outStats.sq[k] += sq
                    }
                    b.y.clear()
                    start += ch
                }
            } finally { listOfNotNull(tA, tB, tX, tM, tR, tY).forEach { it.close() } }
            return outStats
        }
    }

    override fun close() {
        sessions.forEach { runCatching { it.close() } }; sessions.clear(); segs.clear()
        Log.i(TAG, "Kokoro NPU decoder closed")
    }
}
