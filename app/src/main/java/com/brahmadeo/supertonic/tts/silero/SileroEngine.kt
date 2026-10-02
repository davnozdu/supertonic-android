package com.brahmadeo.supertonic.tts.silero

import android.content.Context
import android.util.Log
import com.brahmadeo.supertonic.tts.SupertonicTTS
import org.json.JSONObject
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.LitePyTorchAndroid
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.executorch.EValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Direct Android inference using the exported v5.5 mel/backbone/head files. */
class SileroEngine(context: Context) : AutoCloseable {
    private val root = SileroDownload.root(context)
    private val prefs = context.applicationContext.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
    private var mel: Module? = null
    private var head: Module? = null
    private var backbone: org.pytorch.executorch.Module? = null
    private var lastUsed = android.os.SystemClock.elapsedRealtime()
    private val idle = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "SileroIdle").apply { isDaemon = true } }
    init {
        require(SileroDownload.supported())
        idle.scheduleWithFixedDelay({ synchronized(this) {
            if (mel != null && android.os.SystemClock.elapsedRealtime() - lastUsed > 120000) unload()
        } }, 15, 15, TimeUnit.SECONDS)
    }
    private fun load() {
        if (mel != null) return
        try {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            LitePyTorchAndroid.setNumThreads(threads)
            mel = LiteModuleLoader.load(File(root, "tts_mel.ptl").absolutePath)
            head = LiteModuleLoader.load(File(root, "head.ptl").absolutePath)
            backbone = org.pytorch.executorch.Module.load(File(root, "backbone.pte").absolutePath,
                org.pytorch.executorch.Module.LOAD_MODE_MMAP, threads)
            Log.i("SileroTTS", "Silero v5.5 loaded")
        } catch (t: Throwable) { unload(); throw t }
    }
    @Synchronized fun synthesize(text: String, voice: String, speed: Float, gain: Float,
        listener: SupertonicTTS.ProgressListener?, sid: Long): ByteArray {
        lastUsed = android.os.SystemClock.elapsedRealtime()
        try {
            val prepared = SileroText.prepare(text)
            if (prepared.isEmpty() || SupertonicTTS.isCancelled()) return ByteArray(0)
            require(prepared.length <= 1200) { "Silero sentence is too long" }
            val voiceFile = File(voice)
            require(voiceFile.canonicalFile.parentFile == root.canonicalFile)
            val speaker = JSONObject(voiceFile.readText()).getInt("speaker")
            require(speaker in 0..4)
            load()
            val seq = SileroText.sequence(prepared); val n = seq.size.toLong()
            val shape = longArrayOf(1, n)
            val rates = FloatArray(seq.size) { 1f / speed.coerceIn(.5f, 2.5f) }
            val pitches = FloatArray(seq.size) { 1f }
            val types = SileroText.typeIds(prepared, prefs.getBoolean("silero_intonation", true))
            val t = android.os.SystemClock.elapsedRealtime()
            val out = mel!!.forward(
                IValue.from(Tensor.fromBlob(seq, shape)),
                IValue.from(Tensor.fromBlob(longArrayOf(speaker.toLong()), longArrayOf(1))),
                IValue.from(48000L), IValue.optionalNull(),
                IValue.from(Tensor.fromBlob(rates, shape)), IValue.from(Tensor.fromBlob(pitches, shape)),
                IValue.optionalNull(), IValue.optionalNull(), IValue.from("cpu"), IValue.from(-1L), IValue.from(false),
                IValue.from(Tensor.fromBlob(types, shape)), IValue.optionalNull()
            ).toTuple()[0].toTensor()
            if (SupertonicTTS.isCancelled()) return ByteArray(0)
            val hidden = backbone!!.forward(EValue.from(org.pytorch.executorch.Tensor.fromBlob(out.dataAsFloatArray, out.shape())))[0].toTensor()
            if (SupertonicTTS.isCancelled()) return ByteArray(0)
            val samples = head!!.forward(IValue.from(Tensor.fromBlob(hidden.dataAsFloatArray, hidden.shape())),
                IValue.from(48000L), IValue.from(0.0), IValue.from(true)).toTensor().dataAsFloatArray
            // Keep the requested boost without flattening speech peaks at PCM limits.
            var peak = 0f
            samples.forEach { require(it.isFinite()) { "Silero produced non-finite audio" }; peak = maxOf(peak, kotlin.math.abs(it)) }
            val safeGain = if (peak > 0f) minOf(gain.coerceAtLeast(0f), .98f / peak) else 1f
            val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { pcm.putShort((it * safeGain * 32767).toInt().coerceIn(-32768, 32767).toShort()) }
            val bytes = pcm.array()
            if (SupertonicTTS.isCancelled()) return ByteArray(0)
            // Stream bounded PCM pieces into the existing reader/playback buffer.
            var pos = 0
            while (pos < bytes.size && !SupertonicTTS.isCancelled()) {
                val end = minOf(pos + 48000, bytes.size)
                listener?.onAudioChunk(sid, bytes.copyOfRange(pos, end)); pos = end
            }
            Log.i("SileroTTS", "Synthesized chars=${prepared.length} types=${types.toSet()} ms=${android.os.SystemClock.elapsedRealtime()-t} audioMs=${samples.size*1000L/48000}")
            return if (SupertonicTTS.isCancelled()) ByteArray(0) else bytes
        } finally { lastUsed = android.os.SystemClock.elapsedRealtime() }
    }
    private fun unload() {
        mel?.destroy(); head?.destroy(); backbone?.destroy()
        mel = null; head = null; backbone = null
        Log.i("SileroTTS", "Silero released from RAM")
    }
    @Synchronized override fun close() { idle.shutdownNow(); unload() }
}
