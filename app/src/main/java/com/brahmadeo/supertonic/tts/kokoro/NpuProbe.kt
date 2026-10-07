package com.brahmadeo.supertonic.tts.kokoro

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.brahmadeo.supertonic.tts.utils.AssetManager
import com.brahmadeo.supertonic.tts.utils.Npu
import com.brahmadeo.supertonic.tts.tera.TeraNpuSamplerProbe
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.log10

/** Silent diagnostic: CPU vs NPU for the Tera vocoder windows and the Kokoro model.
 * Reads no settings and changes none; only the compiled NPU graphs are cached once. */
internal object NpuProbe {
    private const val TAG = "SpeechCheck"
    // OnePlus logd drops a process's lines past its quota while QNN compiles, so results also go
    // to a small diagnostic file next to the pushed probe models.
    @Volatile private var results: File? = null
    private fun log(s: String) {
        Log.i(TAG, "NPU PROBE $s")
        results?.let { f -> runCatching { f.appendText("$s\n") } }
    }

    private class Timing(val wallMs: Double, val cpuMs: Double)
    private fun measure(runs: Int, block: () -> Unit): Timing {
        repeat(2) { block() }
        val wall = SystemClock.elapsedRealtimeNanos(); val cpu = Process.getElapsedCpuTime()
        repeat(runs) { block() }
        return Timing((SystemClock.elapsedRealtimeNanos() - wall) / 1e6 / runs, (Process.getElapsedCpuTime() - cpu).toDouble() / runs)
    }
    private fun output(session: OrtSession, inputs: Map<String, OnnxTensor>): FloatArray =
        session.run(inputs).use { r -> val t = r[0] as OnnxTensor; FloatArray(t.info.shape.fold(1L) { a, b -> a * b }.toInt()).also { t.floatBuffer.get(it) } }
    private fun snr(ref: FloatArray, x: FloatArray): String {
        val n = minOf(ref.size, x.size); var sig = 0.0; var err = 0.0; var max = 0f
        for (i in 0 until n) { sig += ref[i].toDouble() * ref[i]; val d = ref[i] - x[i]; err += d.toDouble() * d; max = maxOf(max, abs(d)) }
        return "snrDb=${"%.1f".format(10 * log10(sig / maxOf(err, 1e-20)))} maxAbs=${"%.4f".format(max)} lenRef=${ref.size} len=${x.size}"
    }

    fun run(ctx: Context) {
        results = File(ctx.filesDir, "npu-models").takeIf { it.isDirectory }?.let { File(it, "probe-results.txt") }?.apply { writeText("") }
        log("start supported=${Npu.supported(ctx)} soc=${android.os.Build.SOC_MODEL}")
        val onlyGeneric = File(ctx.filesDir, "npu-models/only-generic").exists()
        if (!onlyGeneric) runCatching { tera(ctx) }.onFailure { log("tera failed ${it.javaClass.simpleName}: ${it.message?.take(300)}") }
        if (!onlyGeneric) runCatching { teraSampler(ctx) }.onFailure { log("tera sampler failed ${it.javaClass.simpleName}: ${it.message?.take(300)}") }
        if (!onlyGeneric) runCatching { kokoro(ctx) }.onFailure { log("kokoro failed ${it.javaClass.simpleName}: ${it.message?.take(300)}") }
        runCatching { generic(ctx) }.onFailure { log("generic failed ${it.javaClass.simpleName}: ${it.message?.take(300)}") }
        // Probe graphs are throwaway: keep flash for the production NPU cache only.
        File(ctx.filesDir, "npu-cache").listFiles { f -> f.name.startsWith("probe-") }?.forEach { it.delete() }
        log("done")
    }

    private fun tera(ctx: Context) {
        val model = File(ctx.filesDir, "${AssetManager.MODEL_VERSION}/tera/models/vocoder.onnx")
        if (!model.isFile) { log("tera skipped: no vocoder"); return }
        val env = OrtEnvironment.getEnvironment()
        for (size in listOf(16, 84)) {
            val random = java.util.Random(1)
            val data = FloatArray(144 * size) { random.nextGaussian().toFloat() }
            val dims = mapOf("batch" to 1L, "generated_latent_length" to size.toLong())
            fun input() = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 144, size.toLong()))
            var reference: FloatArray? = null
            for (threads in listOf(2, 4)) OrtSession.SessionOptions().use { o ->
                o.setIntraOpNumThreads(threads); dims.forEach { (k, v) -> o.setSymbolicDimensionValue(k, v) }
                o.addConfigEntry("session.intra_op.allow_spinning", "0")
                env.createSession(model.path, o).use { s ->
                    input().use { x -> if (reference == null) reference = output(s, mapOf("latent" to x))
                        val t = measure(10) { output(s, mapOf("latent" to x)) }
                        log("tera vocoder frames=$size CPU threads=$threads wallMs=${"%.1f".format(t.wallMs)} cpuMs=${"%.1f".format(t.cpuMs)}") }
                }
            }
            for (mode in listOf("burst", "high_performance", "balanced")) {
                val created = SystemClock.elapsedRealtime()
                Npu.session(ctx, env, model, dims, "probe-tera-vocoder-$size-$mode", mode).use { s ->
                    val createMs = SystemClock.elapsedRealtime() - created
                    input().use { x ->
                        val y = output(s, mapOf("latent" to x))
                        val t = measure(10) { output(s, mapOf("latent" to x)) }
                        log("tera vocoder frames=$size NPU mode=$mode createMs=$createMs wallMs=${"%.1f".format(t.wallMs)} cpuMs=${"%.1f".format(t.cpuMs)} ${snr(reference!!, y)}")
                    }
                }
            }
        }
    }

    /** Ceiling check only: the unrolled 8-step sampler (pushed for the probe) at one exact shape.
     * Frame padding changes the result, so production would need one graph per frame count. */
    private fun teraSampler(ctx: Context) {
        val unrolled = File(ctx.filesDir, "npu-models/sampler_unrolled.onnx")
        val original = File(ctx.filesDir, "${AssetManager.MODEL_VERSION}/tera/models/sampler_distilled_cfg3_8step.onnx")
        if (!unrolled.isFile || !original.isFile) { log("tera sampler skipped: no unrolled model"); return }
        val env = OrtEnvironment.getEnvironment()
        val frames = 60L; val text = 74L; val r = java.util.Random(3)
        fun arr(n: Int, scale: Float = 1f) = FloatArray(n) { r.nextGaussian().toFloat() * scale }
        val noise = arr(144 * 60); val emb = arr(256 * 74, .5f); val style = arr(50 * 256, .5f)
        fun inputs() = mapOf(
            "initial_latent" to OnnxTensor.createTensor(env, FloatBuffer.wrap(noise), longArrayOf(1, 144, frames)),
            "text_emb" to OnnxTensor.createTensor(env, FloatBuffer.wrap(emb), longArrayOf(1, 256, text)),
            "style_ttl" to OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, 50, 256)),
            "latent_mask" to OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(60) { 1f }), longArrayOf(1, 1, frames)),
            "text_mask" to OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(74) { 1f }), longArrayOf(1, 1, text)),
            "guidance" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(3f)), longArrayOf(1)))
        var reference: FloatArray? = null
        OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(2); o.addConfigEntry("session.intra_op.allow_spinning", "0")
            env.createSession(original.path, o).use { s ->
                val x = inputs()
                try { reference = output(s, x); val t = measure(3) { output(s, x) }
                    log("tera sampler CPU threads=2 frames=$frames wallMs=${"%.0f".format(t.wallMs)} cpuMs=${"%.0f".format(t.cpuMs)}") }
                finally { x.values.forEach { it.close() } }
            }
        }
        val created = SystemClock.elapsedRealtime()
        Npu.session(ctx, env, unrolled, mapOf("batch" to 1L, "generated_latent_length" to frames, "text_length" to text),
            "probe-tera-sampler-60x74", allowCpuFallback = true, logInfo = true).use { s ->
            val createMs = SystemClock.elapsedRealtime() - created
            val x = inputs()
            try { val y = output(s, x); val t = measure(3) { output(s, x) }
                log("tera sampler NPU createMs=$createMs wallMs=${"%.0f".format(t.wallMs)} cpuMs=${"%.0f".format(t.cpuMs)} ${snr(reference!!, y)}") }
            finally { x.values.forEach { it.close() } }
        }
    }

    /** Any pushed model: files/npu-models/<name>.probe.json = {"model": file, "dims": {sym: n},
     * "inputs": {name: {"shape": [..], "file": raw little-endian float32}}, "fallback": bool}.
     * Real recorded inputs keep FP16 behaviour representative. */
    private fun generic(ctx: Context) {
        val dir = File(ctx.filesDir, "npu-models")
        val specs = dir.listFiles { f -> f.name.endsWith(".probe.json") }.orEmpty().sortedBy { it.name }
        if (specs.isEmpty()) { log("generic skipped: no specs"); return }
        val env = OrtEnvironment.getEnvironment()
        for (spec in specs) runCatching {
            val j = JSONObject(spec.readText()); val name = spec.name.removeSuffix(".probe.json")
            val model = File(dir, j.getString("model"))
            val dims = j.getJSONObject("dims").let { d -> d.keys().asSequence().associateWith { d.getLong(it) } }
            val ins = j.getJSONObject("inputs")
            val data = ins.keys().asSequence().associateWith { key ->
                val e = ins.getJSONObject(key); val shape = e.getJSONArray("shape").let { a -> LongArray(a.length()) { a.getLong(it) } }
                val bytes = File(dir, e.getString("file")).readBytes()
                shape to FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            }
            fun inputs() = data.mapValues { (_, v) -> OnnxTensor.createTensor(env, FloatBuffer.wrap(v.second), v.first) }
            fun timed(s: OrtSession, label: String, ref: FloatArray?): FloatArray {
                val x = inputs()
                try {
                    val y = output(s, x); val t = measure(3) { output(s, x) }
                    log("generic $name $label wallMs=${"%.1f".format(t.wallMs)} cpuMs=${"%.1f".format(t.cpuMs)} finite=${y.all { it.isFinite() }}" + (ref?.let { " " + snr(it, y) } ?: ""))
                    return y
                } finally { x.values.forEach { it.close() } }
            }
            val reference = OrtSession.SessionOptions().use { o ->
                o.setIntraOpNumThreads(j.optInt("cpuThreads", 4)); o.addConfigEntry("session.intra_op.allow_spinning", "0")
                dims.forEach { (k, v) -> o.setSymbolicDimensionValue(k, v) }
                env.createSession(model.path, o).use { timed(it, "CPU threads=${j.optInt("cpuThreads", 4)}", null) }
            }
            // Variants: {"label": .., "mode": .., "verbose": bool, "qnn": {option: value}}; default burst + high_performance.
            val variants = j.optJSONArray("variants")?.let { a -> List(a.length()) { a.getJSONObject(it) } }
                ?: listOf(JSONObject().put("mode", "burst"), JSONObject().put("mode", "high_performance"))
            for (v in variants) runCatching {
                val mode = v.optString("mode", "high_performance"); val label = v.optString("label", mode)
                val extra = v.optJSONObject("qnn")?.let { q -> q.keys().asSequence().associateWith { q.getString(it) } }.orEmpty()
                val created = SystemClock.elapsedRealtime()
                Npu.session(ctx, env, model, dims, "probe-$name-$label", mode, allowCpuFallback = j.optBoolean("fallback", true), logInfo = j.optBoolean("logInfo", false),
                    extra = extra, verbose = v.optBoolean("verbose", false)).use { s ->
                    log("generic $name NPU $label createMs=${SystemClock.elapsedRealtime() - created}")
                    timed(s, "NPU $label", reference)
                }
            }.onFailure { log("generic $name variant ${v.optString("label")} failed ${it.javaClass.simpleName}: ${it.message?.take(200)}") }
        }.onFailure { log("generic ${spec.name} failed ${it.javaClass.simpleName}: ${it.message?.take(300)}") }
    }

    private fun kokoro(ctx: Context) {
        val root = KokoroDownload.root(ctx)
        val model = File(root, "model.onnx")
        if (!model.isFile || !File(root, "config.json").isFile) { log("kokoro skipped: full model missing"); return }
        val env = OrtEnvironment.getEnvironment()
        check(KokoroPhonemizer.initialize(File(root, "espeak-data").path))
        val vocab = JSONObject(File(root, "config.json").readText()).getJSONObject("vocab").let { o -> o.keys().asSequence().associate { it.single() to o.getInt(it) } }
        val ipa = KokoroG2p.phonemize("Ти́хий ве́чер. За окно́м шелестя́т дере́вья. Вы гото́вы? Тогда́ начнём.", KokoroPhonemizer::phonemes)
        val ids = longArrayOf(0) + ipa.mapNotNull { vocab[it]?.toLong() }.toLongArray() + longArrayOf(0)
        val pack = File(root, "sveta.bin").readBytes()
        val packFloats = FloatArray(pack.size / 4).also { ByteBuffer.wrap(pack).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
        val style = packFloats.copyOfRange((ids.size - 3) * 256, (ids.size - 2) * 256)
        fun inputs() = mapOf("input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())),
            "style" to OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, 256)),
            "speed" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1f)), longArrayOf(1)))
        fun timed(s: OrtSession, label: String) {
            val x = inputs()
            try {
                val y = output(s, x)
                val t = measure(3) { output(s, x) }
                log("kokoro $label tokens=${ids.size} wallMs=${"%.0f".format(t.wallMs)} cpuMs=${"%.0f".format(t.cpuMs)} audioMs=${y.size * 1000L / 24000}")
            } finally { x.values.forEach { it.close() } }
        }
        OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(4); o.addConfigEntry("session.intra_op.allow_spinning", "0")
            env.createSession(model.path, o).use { timed(it, "CPU threads=4") }
        }
        val created = SystemClock.elapsedRealtime()
        Npu.session(ctx, env, model, mapOf("batch_size" to 1L, "input_ids_len" to ids.size.toLong()), "probe-kokoro",
            "high_performance", allowCpuFallback = true, logInfo = true).use { s ->
            log("kokoro NPU session createMs=${SystemClock.elapsedRealtime() - created}")
            timed(s, "NPU+CPU fallback")
        }
    }

    /** Kokoro NPU kit: the same prepared front through the NPU and CPU orchestrations and the original model.
     * Compiling warms the production NPU cache; no settings are read or changed. */
    fun kokoroKit(ctx: Context, modelFile: String = "model.onnx") {
        results = File(ctx.filesDir, "npu-models").apply { mkdirs() }.let { File(it, "probe-results.txt") }.apply { writeText("") }
        log("kokoro-kit start supported=${Npu.supported(ctx)}")
        try {
            val root = KokoroDownload.root(ctx)
            val dimaFile = modelFile.replace("model", "model_dima")
            if (!File(root, modelFile).isFile || !File(root, dimaFile).isFile) { log("kokoro-kit skipped: $modelFile missing"); return }
            log("kokoro-kit model=$modelFile")
            check(KokoroPhonemizer.initialize(File(root, "espeak-data").path))
            val vocab = JSONObject(File(root, "config.json").readText()).getJSONObject("vocab").let { o -> o.keys().asSequence().associate { it.single() to o.getInt(it) } }
            val pack = File(root, "sveta.bin").readBytes().let { b -> FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) } }
            val env = OrtEnvironment.getEnvironment()
            fun created(label: String, block: () -> KokoroNpuDecoder): KokoroNpuDecoder {
                val wall = SystemClock.elapsedRealtime(); val cpu = Process.getElapsedCpuTime()
                return block().also { log("kokoro-kit $label createMs=${SystemClock.elapsedRealtime() - wall} cpuMs=${Process.getElapsedCpuTime() - cpu}") }
            }
            created("NPU cold", { KokoroNpuDecoder(ctx, root, modelFile, 4) }).close()
            created("NPU dima cold", { KokoroNpuDecoder(ctx, root, dimaFile, 4) }).close()
            val npu = created("NPU warm", { KokoroNpuDecoder(ctx, root, modelFile, 4) })
            val dima = created("NPU dima warm", { KokoroNpuDecoder(ctx, root, dimaFile, 4) })
            // Both NPU decoders resident: measure process memory from outside (dmabuf_dump / smaps).
            log("kokoro-kit holding pssKb=${android.os.Debug.getPss()}"); Thread.sleep(20000); dima.close()
            val ref = created("CPU segments", { KokoroNpuDecoder(ctx, root, modelFile, 4, onNpu = false) })
            val original = OrtSession.SessionOptions().use { o -> o.setIntraOpNumThreads(4); o.addConfigEntry("session.intra_op.allow_spinning", "0"); env.createSession(File(root, modelFile).path, o) }
            try {
                for (text in listOf("Ти́хий ве́чер. За окно́м шелестя́т дере́вья.",
                        "Когда́ по́езд наконе́ц останови́лся, на перро́не уже́ никого́ не́ было. Она́ до́лго смотре́ла в окно́ и ду́мала о том, что сказа́л ей муж вчера́ ве́чером.")) {
                    val ipa = KokoroG2p.phonemize(text, KokoroPhonemizer::phonemes)
                    val ids = longArrayOf(0) + ipa.mapNotNull { vocab[it]?.toLong() }.toLongArray() + longArrayOf(0)
                    val style = pack.copyOfRange((ids.size - 3) * 256, (ids.size - 2) * 256)
                    lateinit var pre: KokoroNpuDecoder.Pre
                    val tp = measure(1) { pre = npu.prepare(ids, style, 1f) }
                    lateinit var y: FloatArray; lateinit var yr: FloatArray
                    val calls0 = npu.calls
                    val tn = measure(2) { y = npu.generate(pre) }
                    val callsPer = (npu.calls - calls0) / 4
                    val tr = measure(1) { yr = ref.generate(pre) }
                    val inputs = mapOf("input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())),
                        "style" to OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, 256)),
                        "speed" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1f)), longArrayOf(1)))
                    val to = try { measure(2) { output(original, inputs) } } finally { inputs.values.forEach { it.close() } }
                    val audio = y.size * 1000L / 24000
                    log("kokoro-kit frames=${npu.frames(pre)} audioMs=$audio calls=$callsPer pre wall=${"%.0f".format(tp.wallMs)} cpu=${"%.0f".format(tp.cpuMs)}" +
                        " | NPU gen wall=${"%.0f".format(tn.wallMs)} cpu=${"%.0f".format(tn.cpuMs)}" +
                        " | CPU-seg gen wall=${"%.0f".format(tr.wallMs)} | original wall=${"%.0f".format(to.wallMs)} cpu=${"%.0f".format(to.cpuMs)}" +
                        " | NPU vs CPU-seg ${snr(yr, y)} finite=${y.all { it.isFinite() }}")
                }
            } finally { original.close(); npu.close(); ref.close() }
        } catch (t: Throwable) { log("kokoro-kit failed ${t.javaClass.simpleName}: ${t.message?.take(300)}") }
        log("done")
    }

    /** Tera hybrid sampler vs the CPU 8-step Loop on the same text embedding and noise; silent, read-only. */
    fun teraHybrid(ctx: Context) {
        results = File(ctx.filesDir, "npu-models").apply { mkdirs() }.let { File(it, "probe-results.txt") }.apply { writeText("") }
        log("tera-hybrid start supported=${Npu.supported(ctx)}")
        try {
            val root = File(ctx.filesDir, "${AssetManager.MODEL_VERSION}/tera"); val models = File(root, "models")
            val env = OrtEnvironment.getEnvironment()
            fun cpu(name: String) = OrtSession.SessionOptions().use { o -> o.setIntraOpNumThreads(2); o.addConfigEntry("session.intra_op.allow_spinning", "0")
                env.createSession(File(models, "$name.onnx").path, o) }
            val indexer = org.json.JSONArray(File(root, "unicode_indexer.json").readText()).let { a -> IntArray(a.length()) { a.getInt(it) } }
            val styleFile = File(root, "styles/ru_m1/style_ttl.npy").readBytes()
            val header = (styleFile[8].toInt() and 255) or ((styleFile[9].toInt() and 255) shl 8)
            val style = FloatArray(12800).also { ByteBuffer.wrap(styleFile, 10 + header, 12800 * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            val wall = SystemClock.elapsedRealtime(); val cpu0 = Process.getElapsedCpuTime()
            // One sampler (one QNN context): several instances in one process hung the HTP.
            val sampler = TeraNpuSamplerProbe.create(ctx, models, 4)
            val variants = listOf(1, 2, 4)
            log("tera-hybrid create ms=${SystemClock.elapsedRealtime() - wall} cpuMs=${Process.getElapsedCpuTime() - cpu0}")
            val encoder = cpu("text_encoder"); val loop = cpu("sampler_distilled_cfg3_8step"); val vocoder = cpu("vocoder")
            try {
                for (text in listOf("Он медленно подн+ял голову и посмотр+ел на н+ебо.",
                        "Когда п+оезд наконец остановился, на перр+оне уже никого не было, и только ветер гонял по асфальту старые газеты.")) {
                    val prepared = java.text.Normalizer.normalize("<ru>$text</ru>", java.text.Normalizer.Form.NFKD)
                    val ids = prepared.mapNotNull { c -> c.code.takeIf { it < indexer.size && indexer[it] >= 0 }?.let { indexer[it].toLong() } }.toLongArray()
                    val l = ids.size
                    val enc = mapOf("text_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, l.toLong())),
                        "style_ttl" to OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, 50, 256)),
                        "text_mask" to OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(l) { 1f }), longArrayOf(1, 1, l.toLong())))
                    val emb = try { output(encoder, enc) } finally { enc.values.forEach { it.close() } }
                    val frames = (l * 1.1).toInt().coerceIn(20, 250)
                    val random = java.util.Random(1234); val noise = FloatArray(144 * frames) { random.nextGaussian().toFloat() }
                    lateinit var ref: FloatArray; lateinit var hyb: FloatArray
                    val loopIn = { mapOf("initial_latent" to OnnxTensor.createTensor(env, FloatBuffer.wrap(noise), longArrayOf(1, 144, frames.toLong())),
                        "text_emb" to OnnxTensor.createTensor(env, FloatBuffer.wrap(emb), longArrayOf(1, 256, l.toLong())),
                        "style_ttl" to OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, 50, 256)),
                        "latent_mask" to OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(frames) { 1f }), longArrayOf(1, 1, frames.toLong())),
                        "text_mask" to OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(l) { 1f }), longArrayOf(1, 1, l.toLong())),
                        "guidance" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(3f)), longArrayOf(1))) }
                    val tl = measure(1) { val x = loopIn(); try { ref = output(loop, x) } finally { x.values.forEach { it.close() } } }
                    fun wave(lat: FloatArray): FloatArray { val x = mapOf("latent" to OnnxTensor.createTensor(env, FloatBuffer.wrap(lat), longArrayOf(1, 144, frames.toLong())))
                        return try { output(vocoder, x) } finally { x.values.forEach { it.close() } } }
                    val refWave = wave(ref)
                    log("tera-hybrid text=$l frames=$frames | loop wall=${"%.0f".format(tl.wallMs)} cpu=${"%.0f".format(tl.cpuMs)}")
                    for (steps in variants) {
                        TeraNpuSamplerProbe.setSteps(sampler, steps)
                        val th = measure(1) { hyb = TeraNpuSamplerProbe.sample(sampler, noise, frames, emb, l, style) }
                        log("tera-hybrid   npuSteps=$steps wall=${"%.0f".format(th.wallMs)} cpu=${"%.0f".format(th.cpuMs)} | latent ${snr(ref, hyb)} | audio ${snr(refWave, wave(hyb))} | ${TeraNpuSamplerProbe.counters(sampler)}")
                    }
                }
            } finally { encoder.close(); loop.close(); vocoder.close(); sampler.close() }
        } catch (t: Throwable) { log("tera-hybrid failed ${t.javaClass.simpleName}: ${t.message?.take(300)}") }
        log("done")
    }
}

