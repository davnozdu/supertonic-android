package com.brahmadeo.supertonic.tts.llm

import android.content.Context
import android.os.Build
import java.io.File

/** Gemma 4 E2B Q4_0 on the Hexagon NPU via llama.cpp ggml-hexagon (cpp/gemma_npu). Experimental local engine,
 * an alternative to LiteRT GPU; never both. The DSP loads its skel from the app's native library directory,
 * so ADSP_LIBRARY_PATH must be set before libggml is loaded. */
internal object GemmaHexagon {
    private var loaded = false
    fun supported(ctx: Context) = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" &&
        File(ctx.applicationInfo.nativeLibraryDir, "libgemma_npu.so").isFile

    @Synchronized private fun library(ctx: Context) {
        if (loaded) return
        val dir = ctx.applicationInfo.nativeLibraryDir
        android.system.Os.setenv("ADSP_LIBRARY_PATH", "$dir;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", true)
        System.loadLibrary("gemma_npu")
        loaded = true
    }

    class Model internal constructor(private var handle: Long) : AutoCloseable {
        /** Greedy completion of an already templated prompt; throws on cancel, context overflow or NPU errors. */
        fun generate(prompt: String, maxTokens: Int): String =
            String(nativeGenerate(handle, prompt, maxTokens) ?: ByteArray(0), Charsets.UTF_8)
        fun cancel() { if (handle != 0L) nativeCancel(handle) }
        fun stats(): String = if (handle != 0L) nativeStats(handle) else ""
        override fun close() { if (handle != 0L) { nativeFree(handle); handle = 0L } }
    }

    fun load(ctx: Context, model: File, contextTokens: Int = 4096, threads: Int = 2): Model {
        library(ctx)
        return Model(nativeLoad(model.absolutePath, contextTokens, threads, "HTP0"))
    }

    /** Gemma 4 chat format (verified byte-exact by the model's authors); instructions go into the user turn. */
    fun prompt(system: String, user: String) = "<|turn>user\n$system\n\n$user<turn|>\n<|turn>model\n"

    @JvmStatic private external fun nativeLoad(model: String, contextTokens: Int, threads: Int, device: String): Long
    @JvmStatic private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): ByteArray?
    @JvmStatic private external fun nativeCancel(handle: Long)
    @JvmStatic private external fun nativeStats(handle: Long): String
    @JvmStatic private external fun nativeFree(handle: Long)
}
