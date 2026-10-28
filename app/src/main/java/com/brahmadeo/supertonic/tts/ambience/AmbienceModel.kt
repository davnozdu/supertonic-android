package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import android.os.Build
import android.util.Log
import com.brahmadeo.supertonic.tts.utils.Npu
import com.brahmadeo.supertonic.tts.utils.ResumableModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Stable Audio 3 Small SFX (Stability AI Community License; T5Gemma under the Gemma Terms of Use; "Powered by
 * Stability AI"). Our GitHub release ambience-sa3-v1 mirrors the official LiteRT build of
 * stabilityai/stable-audio-3-optimized@08b17423 unchanged, plus the NPU graphs we derived from its dit_fp16.onnx
 * (changes listed in NOTICE.txt). Everything is pinned by size and SHA-256 and downloaded only on request; the
 * NPU graphs (≈0.9 GB) only when the NPU switch is on. */
object AmbienceModel {
    private const val BASE = "https://github.com/davnozdu/supertonic-android/releases/download/ambience-sa3-v1"
    const val NPU_KEY = "ambience_npu"
    data class Asset(val name: String, val size: Long, val sha: String)
    val ASSETS = listOf(
        Asset("tokenizer.model", 4_241_003L, "61a7b147390c64585d6c3543dd6fc636906c9af3865a5548f27f31aee1d4c8e2"),
        Asset("encoder_fp16.tflite", 563_818_608L, "8530d0b3e6b9b9dcf1239145c2a853fb749708eaddbb472ff8f0802b50059372"),
        Asset("dit_w8a8.tflite", 539_350_360L, "d3f99409f446eff9a625768ba2ee09a2347ea90c4897c1662fbcd8837fae3045"),
        Asset("dec_w8a8.tflite", 89_329_616L, "90cad5ef81e6b18eb205012aee03bc53ed59e1c17033b79a84a4612674b1e03a"),
        Asset("LICENSE.md", 11_852L, "d6f6b1a4dce5c852bd6d7d9482d002baf0ccdb71e662250b73be9eec8764ee8d"),
        Asset("LICENSE_GEMMA.md", 10_541L, "e77acc0d3163bb7534675045c584b4d04b387b529239fc4b3647da0a01ba4745"),
        Asset("NOTICE.txt", 2_049L, "9cb849e59ae91296694324860e83d90dcbde7ac3c5f5d27ef5dbb79969a1051c"),
    )
    /** 30 s DiT for the HTP (all-fp16 safe) and its fp32 CPU front for everything that depends only on t/seconds. */
    val NPU_ASSETS = listOf(
        Asset("dit_L323_main.onnx", 923_766_247L, "e30c054b1a34a2eea6e25c19f668192e619327126486893b358547deab9c53af"),
        Asset("dit_pre.onnx", 21_792_854L, "7611401792c7bb24a0c1af4bca88cd877aca6ec8ba63cbe53773cdfe56c5294d"),
    )
    /** «Музыка по настроению»: Stable Audio 3 Small Music (same T5 and decoder), its NPU graphs made the same way. */
    val MUSIC_ASSETS = listOf(
        Asset("dit_music_w8a8.tflite", 539_350_360L, "1d8ef99bf10f51cc52b68f7b527d3773828e700758b253edd8a8e088a906e241"),
    )
    val MUSIC_NPU_ASSETS = listOf(
        Asset("music_dit_L323_main.onnx", 923_780_604L, "b76f554f3f66c9b82307a352c72a6a3db5fb116fac057e6aee3b747a2be1408b"),
        Asset("music_dit_pre.onnx", 21_792_854L, "4cb8277b82ce3569323543f10360803addca880b6d706e8095cc64403966e168"),
    )
    /** Stable Audio 3 Medium (1.4 B, music only): clearer timbre, ~2.4× the CPU of the small one; same T5 and latents. */
    val MEDIUM_ASSETS = listOf(
        Asset("dit_medium_w8a8.tflite", 1_667_708_664L, "e60ea3a8b530de93ee7f1fd7b4426597006dbcde7bce45750d39c439f4ef9602"),
        Asset("NOTICE_MEDIUM.txt", 1_728L, "acde99a66b7e912b9c9d3cf6da2784a7ee70f5314e07f6d7a85b7c878c2116aa"),
    )
    /** The medium DiT for the HTP in three parts (layers 0–7, 8–15, 16–23; each compiles on its own within the
     * phone's memory and stays < 2 GB as an embedded context) and its fp32 CPU front. */
    val MEDIUM_NPU_ASSETS = listOf(
        Asset("medium_dit_L323_r_p0.onnx", 960_813_101L, "2fd544d27814af2ecdbdac519639f9a54117fd80dd46c6a75b6a189e244632c4"),
        Asset("medium_dit_L323_r_p1.onnx", 952_607_398L, "1d73f64d7bb89d6cc81c69b0ed0d241906b4ed793daf362aa0ce4bc0a33b89de"),
        Asset("medium_dit_L323_r_p2.onnx", 953_528_040L, "0ffd507bc84d447f6b2c279e65bef12d3ec19a69ef9d60d57b8e1c8a7b77f94d"),
        Asset("medium_dit_pre.onnx", 47_063_721L, "7134b158307db424298c3896f4e96b06d6091336895523823fab2a9ad1f62d37"),
    )
    /** SAME-L decoder (the same latents as SAME-S): more detail, ~+30 % of the generation time. */
    val LARGE_DECODER = listOf(
        Asset("dec_l_w8a8.tflite", 515_381_432L, "53dbca41ec9620257834bda4f3008a2cd5072afba564b84906f8ffdfca2647e7"),
    )
    const val MUSIC_MODEL_KEY = "music_model"
    const val LARGE_DECODER_KEY = "music_decoder_large"
    private const val LARGE_DECODER_MARKER = "verified-ambience-sa3-v1-dec-l"

    /** One music model: its CPU DiT (rung signatures), its NPU graphs (30 s) and their compiled-cache names. */
    class Music(val id: String, val title: String, val ditFile: String, val rungs: IntArray, val assets: List<Asset>, val marker: String,
                val npuAssets: List<Asset>, val npuMarker: String, val npuParts: List<String>, val npuPre: String, val npuCache: String, val family: String,
                /** Where an NPU failure of this model is recorded. */
                val failScope: String = Npu.AMBIENCE)
    val SMALL_MUSIC = Music("small", "Малая", "dit_music_w8a8.tflite", Sa3Generator.SMALL_RUNGS, MUSIC_ASSETS, "verified-ambience-sa3-v1-music",
        MUSIC_NPU_ASSETS, "verified-ambience-sa3-v1-music-npu", listOf("music_dit_L323_main.onnx"), "music_dit_pre.onnx", "sa3-music-L323-b76f554f", "sa3-music")
    val MEDIUM_MUSIC = Music("medium", "Средняя", "dit_medium_w8a8.tflite", Sa3Generator.MEDIUM_RUNGS, MEDIUM_ASSETS, "verified-ambience-sa3-v1-medium",
        MEDIUM_NPU_ASSETS, "verified-ambience-sa3-v1-medium-npu2", (0..2).map { "medium_dit_L323_r_p$it.onnx" }, "medium_dit_pre.onnx", "sa3-medium-L323-2fd544d2", "sa3-medium",
        Npu.AMBIENCE_MEDIUM)
    private fun prefs(context: Context) = context.getSharedPreferences("SupertonicPrefs", 0)
    /** The chosen music model (pref [MUSIC_MODEL_KEY], the small one by default). */
    fun music(context: Context) = if (prefs(context).getString(MUSIC_MODEL_KEY, "small") == "medium") MEDIUM_MUSIC else SMALL_MUSIC
    fun largeDecoderWanted(context: Context) = prefs(context).getBoolean(LARGE_DECODER_KEY, false)
    fun largeDecoderReady(context: Context) = complete(context, LARGE_DECODER, LARGE_DECODER_MARKER)
    /** The decoder for music: SAME-L when switched on and downloaded, else SAME-S. */
    fun musicDecoder(context: Context) = if (largeDecoderWanted(context) && largeDecoderReady(context)) "dec_l_w8a8.tflite" else "dec_w8a8.tflite"
    fun musicBytes(context: Context) = music(context).assets.sumOf { it.size } + (if (largeDecoderWanted(context)) LARGE_DECODER.sumOf { it.size } else 0L)
    fun musicNpuBytes(context: Context) = music(context).npuAssets.sumOf { it.size }
    fun musicReady(context: Context) = ready(context) && music(context).let { complete(context, it.assets, it.marker) } &&
        (!largeDecoderWanted(context) || largeDecoderReady(context))
    /** False while a model has no NPU graphs (the CPU makes its music then). */
    fun musicNpuReady(context: Context) = musicReady(context) && music(context).let { it.npuAssets.isNotEmpty() && complete(context, it.npuAssets, it.npuMarker) }
    private fun musicNpuMissing(context: Context) = music(context).npuAssets.isNotEmpty() && !musicNpuReady(context)
    /** A generator for music with the chosen model and decoder. */
    fun musicGenerator(context: Context, threads: Int, npu: Sa3Generator.NpuDit?) = music(context).let {
        Sa3Generator(root(context), threads, npu, it.ditFile, it.rungs, musicDecoder(context))
    }
    private fun musicWanted(context: Context) = AmbienceSettings.moodMusic(context.getSharedPreferences("SupertonicPrefs", 0))

    const val NPU_LENGTH = 323
    private const val NPU_CACHE = "sa3-dit-L323-e30c054b"
    private val lock = Mutex()
    private const val PARALLEL = 3
    private const val MARKER = "verified-ambience-sa3-v1"
    private const val NPU_MARKER = "verified-ambience-sa3-v1-npu2"

    fun supported() = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
    fun root(context: Context) = File(context.filesDir, "ambience/sa3-small-sfx")
    fun totalBytes() = ASSETS.sumOf { it.size }
    fun npuBytes() = NPU_ASSETS.sumOf { it.size }

    private fun complete(context: Context, assets: List<Asset>, marker: String) =
        File(root(context), marker).exists() && assets.all { File(root(context), it.name).length() == it.size }
    fun ready(context: Context) = supported() && complete(context, ASSETS, MARKER)
    fun npuReady(context: Context) = ready(context) && complete(context, NPU_ASSETS, NPU_MARKER)

    /** Own switch, off by default; also needs an HTP and no recorded build failure for this app version. */
    fun npuWanted(context: Context, scope: String = Npu.AMBIENCE) = context.getSharedPreferences("SupertonicPrefs", 0).getBoolean(NPU_KEY, false) &&
        Npu.supported(context) && !Npu.failed(context, scope)

    /** Up to [PARALLEL] files at once (each resumable and SHA-checked on its own): one HTTP stream from GitHub is
     * often throttled, and the model is three large files. */
    private suspend fun fetch(context: Context, assets: List<Asset>, marker: String, label: String, progress: (String, Float) -> Unit) {
        val dir = root(context).apply { mkdirs() }
        val total = assets.sumOf { it.size }
        val received = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val gate = kotlinx.coroutines.sync.Semaphore(PARALLEL)
        kotlinx.coroutines.coroutineScope {
            for (asset in assets.sortedByDescending { it.size }) launch { gate.withPermit {
                val target = File(dir, asset.name)
                // A shorter file left by an older pinned version is not a partial download: resuming would splice two
                // versions. The checksum catches it (and deletes the file); one clean retry then fetches it whole.
                for (attempt in 0..1) try {
                    ResumableModelFile.fetch("$BASE/${asset.name}", target, asset.size, asset.sha) { got, _ ->
                        received[asset.name] = got
                        val sum = received.values.sum()
                        progress("$label: ${sum / 1048576} / ${total / 1048576} МБ", sum.toFloat() / total)
                    }
                    break
                } catch (e: IllegalStateException) { if (attempt == 1) throw e; target.delete() }
                received[asset.name] = asset.size
            } }
        }
        File(dir, marker).writeText("ok")
    }

    /** The CPU model, plus the NPU graphs when the NPU switch is on, plus the music model when «Музыка по настроению» is. */
    suspend fun download(context: Context, progress: (String, Float) -> Unit) =
        com.brahmadeo.supertonic.tts.utils.ModelDownloadForeground.run(context) {
            withContext(Dispatchers.IO) { lock.withLock {
                require(supported()) { "Генератор фоновых звуков требует ARM64" }
                if (!ready(context)) fetch(context, ASSETS, MARKER, "Звуки", progress)
                if (npuWanted(context) && !npuReady(context)) fetch(context, NPU_ASSETS, NPU_MARKER, "Звуки · NPU", progress)
                if (musicWanted(context)) fetchMusic(context, progress)
                progress("Генератор звуков установлен", 1f)
            } }
        }

    /** «Своя музыка»: the base model and the music model (+ its NPU graphs when the NPU is on), whatever the book switches. */
    suspend fun downloadMusic(context: Context, progress: (String, Float) -> Unit) =
        com.brahmadeo.supertonic.tts.utils.ModelDownloadForeground.run(context) {
            withContext(Dispatchers.IO) { lock.withLock {
                require(supported()) { "Генератор музыки требует ARM64" }
                if (!ready(context)) fetch(context, ASSETS, MARKER, "Звуки", progress)
                fetchMusic(context, progress)
                progress("Модель музыки установлена", 1f)
            } }
        }

    /** The chosen music model, the large decoder when switched on, the NPU graphs when the NPU is on. */
    private suspend fun fetchMusic(context: Context, progress: (String, Float) -> Unit) {
        val m = music(context)
        if (!complete(context, m.assets, m.marker)) fetch(context, m.assets, m.marker, "Музыка · ${m.title.lowercase()}", progress)
        if (largeDecoderWanted(context) && !largeDecoderReady(context)) fetch(context, LARGE_DECODER, LARGE_DECODER_MARKER, "Большой декодер", progress)
        if (npuWanted(context, m.failScope) && musicNpuMissing(context)) fetch(context, m.npuAssets, m.npuMarker, "Музыка · NPU", progress)
    }

    /** Deletes the model, the NPU graphs and their compiled HTP cache. */
    suspend fun remove(context: Context) = withContext(Dispatchers.IO) { lock.withLock {
        root(context).deleteRecursively()
        File(context.filesDir, "npu-cache").listFiles()?.filter { it.name.startsWith("sa3-dit-") || it.name.startsWith("sa3-music-") || it.name.startsWith("sa3-medium-") }?.forEach { it.delete() }
    } }
    fun installedBytes(context: Context) = root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** DiT on the NPU for 30 s clips, or null (CPU) when it is off, not downloaded or fails to build. A build
     * failure turns the NPU off for this app version; the first build compiles the HTP graph (~2 min, cached). */
    /** One NPU DiT: its graph files, CPU front, cache names (one per part) and failure scope. */
    private class NpuTarget(val parts: List<String>, val pre: String, val cache: String, val family: String, val scope: String, val names: List<String>, val label: String)
    private fun target(context: Context, music: Boolean): NpuTarget {
        val m = music(context)
        val parts = if (music) m.npuParts else listOf("dit_L323_main.onnx")
        val cache = if (music) m.npuCache else NPU_CACHE
        // One graph keeps its old cache name; the parts of a cut model get "-p<k>" and keep each other's caches.
        return NpuTarget(parts, if (music) m.npuPre else "dit_pre.onnx", cache, if (music) m.family else "sa3-dit",
            if (music) m.failScope else Npu.AMBIENCE, if (parts.size == 1) listOf(cache) else parts.indices.map { "$cache-p$it" },
            if (music) "музыка, ${m.title.lowercase()}" else "звуки")
    }
    private fun npuUsable(context: Context, music: Boolean) =
        npuWanted(context, target(context, music).scope) && (if (music) musicNpuReady(context) else npuReady(context))
    /** True when this DiT would use the NPU but its graphs are not compiled yet. */
    fun npuUncompiled(context: Context, music: Boolean) =
        npuUsable(context, music) && target(context, music).names.any { !Npu.cacheReady(context, it) }

    /** DiT on the NPU for 30 s clips, or null (CPU) when it is off, not downloaded or fails to build. A build
     * failure turns the NPU off for this model and app version. The first build compiles the HTP graphs (the small
     * models ~2 min, the medium one up to an hour), part by part, each cached as soon as it is made. With
     * «Готовить NPU ночью» ([allowCompile] false) nothing is compiled here: the CPU makes the clip and the night job
     * compiles. [onCompile] (part, parts) before each part compiled; [stop] is checked between parts. */
    fun npuDit(context: Context, music: Boolean = false, allowCompile: Boolean = !AmbienceSettings.npuNight(prefs(context)),
               onCompile: (Int, Int) -> Unit = { _, _ -> }, stop: () -> Boolean = { false }): Sa3Generator.NpuDit? {
        if (!npuUsable(context, music)) return null
        val t = target(context, music)
        if (!allowCompile && t.names.any { !Npu.cacheReady(context, it) }) return null
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        fun open(k: Int) = Npu.session(context, env, File(root(context), t.parts[k]), emptyMap(), t.names[k], family = t.family, siblings = t.names)
        val sessions = ArrayList<ai.onnxruntime.OrtSession>()
        var building = -1
        return try {
            // Compile the missing parts one at a time and free each (a compile peaks at several GB), then load all
            // from the cache. family: older compiled graphs (~1 GB each) are deleted, not only older runtimes.
            for (k in t.parts.indices) if (!Npu.cacheReady(context, t.names[k])) {
                if (stop()) return null
                onCompile(k + 1, t.parts.size); building = k
                if (t.parts.size > 1) open(k).close() else sessions.add(open(k))
                building = -1
            }
            if (stop()) { sessions.forEach { it.close() }; return null }
            if (sessions.isEmpty()) for (k in t.parts.indices) sessions.add(open(k))
            Log.i("Ambience", "NPU session ready (music=$music, parts=${t.parts.size}), PSS ${android.os.Debug.getPss() / 1024} MB")
            val preSession = env.createSession(File(root(context), t.pre).path, ai.onnxruntime.OrtSession.SessionOptions())
            Sa3Generator.NpuDit(sessions, preSession, NPU_LENGTH)
        } catch (e: Throwable) {
            sessions.forEach { runCatching { it.close() } }
            Log.w("Ambience", "NPU DiT unavailable: ${e.javaClass.simpleName}: ${e.message}")
            // A rejected build may still have written its context (~1 GB): never keep or reuse it. Parts compiled
            // before it stay (a load failure of a cached part drops them all).
            val drop = if (building >= 0) listOf(t.names[building]) else t.names
            File(context.filesDir, "npu-cache").listFiles()?.filter { f -> drop.any { f.name.startsWith("$it-") } }?.forEach { it.delete() }
            Npu.markFailed(context, "${e.javaClass.simpleName}: ${e.message}", t.scope)
            null
        }
    }

    /** «Готовить NPU ночью»: compiles every NPU DiT that would be used and is not compiled yet (the sounds, the chosen
     * music model), part by part; stops between parts when [stop] says so (unplugged, «Остановить»). */
    fun compileMissing(context: Context, stop: () -> Boolean, progress: (String) -> Unit): Boolean {
        for (music in listOf(false, true)) {
            if (!npuUncompiled(context, music)) continue
            val label = target(context, music).label
            npuDit(context, music, allowCompile = true, onCompile = { k, n -> progress("Подготовка NPU ($label): часть $k из $n") }, stop = stop)?.close()
            if (stop()) return false
        }
        return !npuUncompiled(context, false) && !npuUncompiled(context, true)
    }

}
