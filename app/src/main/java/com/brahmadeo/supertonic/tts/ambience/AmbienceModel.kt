package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import android.os.Build
import android.util.Log
import com.brahmadeo.supertonic.tts.utils.Npu
import com.brahmadeo.supertonic.tts.utils.ResumableModelFile
import kotlinx.coroutines.Dispatchers
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
        Asset("NOTICE.txt", 1_736L, "17699e05140c040456d1f3d82840d8a0e8cd630c0256055646fdfcf1ef00a699"),
    )
    /** 30 s DiT for the HTP (all-fp16 safe) and its fp32 CPU front for everything that depends only on t/seconds. */
    val NPU_ASSETS = listOf(
        Asset("dit_L323_main.onnx", 923_766_247L, "e30c054b1a34a2eea6e25c19f668192e619327126486893b358547deab9c53af"),
        Asset("dit_pre.onnx", 21_792_854L, "7611401792c7bb24a0c1af4bca88cd877aca6ec8ba63cbe53773cdfe56c5294d"),
    )
    const val NPU_LENGTH = 323
    private const val NPU_CACHE = "sa3-dit-L323-e30c054b"
    private val lock = Mutex()
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
    fun npuWanted(context: Context) = context.getSharedPreferences("SupertonicPrefs", 0).getBoolean(NPU_KEY, false) &&
        Npu.supported(context) && !Npu.failed(context, Npu.AMBIENCE)

    private suspend fun fetch(context: Context, assets: List<Asset>, marker: String, label: String, progress: (String, Float) -> Unit) {
        val dir = root(context).apply { mkdirs() }
        val total = assets.sumOf { it.size }; var done = 0L
        for (asset in assets) {
            ResumableModelFile.fetch("$BASE/${asset.name}", File(dir, asset.name), asset.size, asset.sha) { received, _ ->
                progress("$label: ${(done + received) / 1048576} / ${total / 1048576} МБ", (done + received).toFloat() / total)
            }
            done += asset.size
        }
        File(dir, marker).writeText("ok")
    }

    /** The CPU model, plus the NPU graphs when the switch is on. */
    suspend fun download(context: Context, progress: (String, Float) -> Unit) =
        com.brahmadeo.supertonic.tts.utils.ModelDownloadForeground.run(context) {
            withContext(Dispatchers.IO) { lock.withLock {
                require(supported()) { "Генератор фоновых звуков требует ARM64" }
                if (!ready(context)) fetch(context, ASSETS, MARKER, "Звуки", progress)
                if (npuWanted(context) && !npuReady(context)) fetch(context, NPU_ASSETS, NPU_MARKER, "Звуки · NPU", progress)
                progress("Генератор звуков установлен", 1f)
            } }
        }

    /** Deletes the model, the NPU graphs and their compiled HTP cache. */
    suspend fun remove(context: Context) = withContext(Dispatchers.IO) { lock.withLock {
        root(context).deleteRecursively()
        File(context.filesDir, "npu-cache").listFiles()?.filter { it.name.startsWith("sa3-dit-") }?.forEach { it.delete() }
    } }
    fun installedBytes(context: Context) = root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** DiT on the NPU for 30 s clips, or null (CPU) when it is off, not downloaded or fails to build. A build
     * failure turns the NPU off for this app version; the first build compiles the HTP graph (~2 min, cached). */
    fun npuDit(context: Context): Sa3Generator.NpuDit? {
        if (!npuWanted(context) || !npuReady(context)) return null
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        return try {
            val session = Npu.session(context, env, File(root(context), "dit_L323_main.onnx"), emptyMap(), NPU_CACHE)
            val pre = try { env.createSession(File(root(context), "dit_pre.onnx").path, ai.onnxruntime.OrtSession.SessionOptions()) }
                catch (e: Throwable) { session.close(); throw e }
            Sa3Generator.NpuDit(session, pre, NPU_LENGTH)
        } catch (e: Throwable) {
            Log.w("Ambience", "NPU DiT unavailable: ${e.javaClass.simpleName}: ${e.message}")
            // A rejected build may still have written its context (~1 GB): never keep or reuse it.
            File(context.filesDir, "npu-cache").listFiles()?.filter { it.name.startsWith(NPU_CACHE) }?.forEach { it.delete() }
            Npu.markFailed(context, "${e.javaClass.simpleName}: ${e.message}", Npu.AMBIENCE)
            null
        }
    }
}
