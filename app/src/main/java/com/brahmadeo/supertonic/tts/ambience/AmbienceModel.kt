package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import android.os.Build
import com.brahmadeo.supertonic.tts.utils.ResumableModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Stable Audio 3 Small SFX (Stability AI Community License; T5Gemma under the Gemma Terms of Use), the official
 * LiteRT build from stabilityai/stable-audio-3-optimized, pinned by revision, size and SHA-256. Downloaded only on
 * request, straight from Hugging Face (the repo is not gated). */
object AmbienceModel {
    private const val BASE = "https://huggingface.co/stabilityai/stable-audio-3-optimized/resolve/08b17423649b5714b22eab20f96bf00751f2358a"
    data class Asset(val path: String, val size: Long, val sha: String) { val name get() = path.substringAfterLast('/') }
    val ASSETS = listOf(
        Asset("tokenizer.model", 4_241_003L, "61a7b147390c64585d6c3543dd6fc636906c9af3865a5548f27f31aee1d4c8e2"),
        Asset("tflite/t5gemma/encoder_fp16.tflite", 563_818_608L, "8530d0b3e6b9b9dcf1239145c2a853fb749708eaddbb472ff8f0802b50059372"),
        Asset("tflite/sa3-sm-sfx/dit_w8a8.tflite", 539_350_360L, "d3f99409f446eff9a625768ba2ee09a2347ea90c4897c1662fbcd8837fae3045"),
        Asset("tflite/same-s/dec_w8a8.tflite", 89_329_616L, "90cad5ef81e6b18eb205012aee03bc53ed59e1c17033b79a84a4612674b1e03a"),
        Asset("LICENSE.md", 11_852L, "d6f6b1a4dce5c852bd6d7d9482d002baf0ccdb71e662250b73be9eec8764ee8d"),
        Asset("LICENSE_GEMMA.md", 10_541L, "e77acc0d3163bb7534675045c584b4d04b387b529239fc4b3647da0a01ba4745"),
    )
    private val lock = Mutex()
    private const val MARKER = "verified-08b17423"

    fun supported() = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
    fun root(context: Context) = File(context.filesDir, "ambience/sa3-small-sfx")
    fun file(context: Context, name: String) = File(root(context), name)
    fun totalBytes() = ASSETS.sumOf { it.size }

    fun ready(context: Context) = supported() && File(root(context), MARKER).exists() &&
        ASSETS.all { file(context, it.name).length() == it.size }

    suspend fun download(context: Context, progress: (String, Float) -> Unit) =
        com.brahmadeo.supertonic.tts.utils.ModelDownloadForeground.run(context) {
            withContext(Dispatchers.IO) { lock.withLock {
                require(supported()) { "Генератор фоновых звуков требует ARM64" }
                if (ready(context)) { progress("Генератор звуков установлен", 1f); return@withLock }
                val dir = root(context).apply { mkdirs() }
                val total = totalBytes(); var complete = 0L
                for (asset in ASSETS) {
                    val target = File(dir, asset.name)
                    ResumableModelFile.fetch("$BASE/${asset.path}", target, asset.size, asset.sha) { received, _ ->
                        progress("Звуки: ${(complete + received) / 1048576} / ${total / 1048576} МБ", (complete + received).toFloat() / total)
                    }
                    complete += asset.size
                }
                File(dir, MARKER).writeText("ok")
                progress("Генератор звуков установлен", 1f)
            } }
        }
}
