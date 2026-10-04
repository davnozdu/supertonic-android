package com.brahmadeo.supertonic.tts.pocket

import kotlin.concurrent.read
import kotlin.concurrent.write

/** Thin lifetime-safe JNI wrapper around the streaming PocketTTS C API. */
internal class NativePocketTts(
    modelsDir: String,
    voicesDir: String,
    precision: String,
    temperature: Float,
    lsdSteps: Int,
    threads: Int,
    sentencePauseMs: Int,
    maxTextTokens: Int,
    eosExtra: Int = -1
) : AutoCloseable {
    private var handle: Long = 0
    private val lifetime = java.util.concurrent.locks.ReentrantReadWriteLock()

    init {
        System.loadLibrary("pockettts_jni")
        handle = nativeCreate(
            modelsDir,
            voicesDir,
            precision,
            temperature,
            lsdSteps,
            threads,
            sentencePauseMs,
            maxTextTokens,
            eosExtra
        )
        check(handle != 0L) { "Не удалось загрузить Shtorm PocketTTS." }
    }

    fun synthesize(text: String, voiceFile: String, speed: Float, sink: AudioSink): Boolean =
        lifetime.read { check(handle!=0L); nativeSynthesize(handle, text, voiceFile, speed, sink) }
    fun stop() = lifetime.read { if (handle != 0L) nativeStop(handle) }
    override fun close() = lifetime.write { if (handle != 0L) nativeDestroy(handle).also { handle = 0 } }

    interface AudioSink { fun onAudio(samples: FloatArray): Boolean }

    private external fun nativeCreate(
        modelsDir: String,
        voicesDir: String,
        precision: String,
        temperature: Float,
        lsdSteps: Int,
        threads: Int,
        sentencePauseMs: Int,
        maxTextTokens: Int,
        eosExtra: Int
    ): Long
    private external fun nativeSynthesize(handle: Long, text: String, voiceFile: String, speed: Float, sink: AudioSink): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)
}
