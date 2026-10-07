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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Direct Android inference using the exported v5.5 mel/backbone/head files. */
class SileroEngine(context: Context, val threads: Int = com.brahmadeo.supertonic.tts.utils.EngineThreads.selected(context)) : AutoCloseable {
    private val root = SileroDownload.root(context)
    private val metadata = JSONObject(File(root, "pack.json").readText())
    private val nativeTypes = metadata.optBoolean("types", true)
    private val symbolIds = metadata.getJSONObject("symbol_to_id")
    private val speakers = metadata.getJSONObject("speakers")
    private val prefs = context.applicationContext.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
    private var mel: Module? = null
    private var head: Module? = null
    private var backbone: org.pytorch.executorch.Module? = null
    private var lastUsed = android.os.SystemClock.elapsedRealtime()
    private val idle = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "SileroIdle").apply { isDaemon = true } }
    init {
        require(SileroDownload.supported())
        require(threads in 1..16)
        idle.scheduleWithFixedDelay({ synchronized(this) {
            if (mel != null && android.os.SystemClock.elapsedRealtime() - lastUsed > 120000) unload()
        } }, 15, 15, TimeUnit.SECONDS)
    }
    private fun load() {
        if (mel != null) return
        try {
            LitePyTorchAndroid.setNumThreads(threads)
            mel = LiteModuleLoader.load(File(root, "tts_mel.ptl").absolutePath)
            head = LiteModuleLoader.load(File(root, "head.ptl").absolutePath)
            backbone = org.pytorch.executorch.Module.load(File(root, "backbone.pte").absolutePath,
                org.pytorch.executorch.Module.LOAD_MODE_MMAP, threads)
            Log.i("SileroTTS", "Silero v5.5 loaded threads=$threads")
        } catch (t: Throwable) { unload(); throw t }
    }
    @Synchronized fun synthesize(text: String, voice: String, speed: Float, gain: Float,
        listener: SupertonicTTS.ProgressListener?, sid: Long): ByteArray {
        val fixedPauses=prefs.getBoolean("silero_fixed_pauses",false)
        val sentenceMs=if(fixedPauses) prefs.getInt("tera_sentence_pause_ms",420).coerceIn(0,900) else 0
        val parts=SileroText.phrases(text,sentenceMs)
        val output=java.io.ByteArrayOutputStream()
        for(part in parts) {
            if(SupertonicTTS.isCancelled()) return ByteArray(0)
            val bytes=synthesizePart(part,voice,speed,gain,listener,sid)
            if(SupertonicTTS.isCancelled()) return ByteArray(0)
            if(bytes.isEmpty()) { Log.w("SileroTTS","Phrase without Silero symbols skipped chars=${part.length}"); continue }
            output.write(bytes)
        }
        return output.toByteArray()
    }
    private fun synthesizePart(text: String, voice: String, speed: Float, gain: Float,
        listener: SupertonicTTS.ProgressListener?, sid: Long): ByteArray {
        lastUsed = android.os.SystemClock.elapsedRealtime()
        try {
            val source = SileroText.prepare(text)
            // These end-symbol quirks were measured on v5.5 RU; other voice packs
            // keep their original input until independently verified.
            val input = if (nativeTypes) SileroText.modelInput(source) else SileroText.ModelInput(source, false)
            val prepared = if (nativeTypes) input.text else input.text.replace('–', '—')
            if (prepared.isEmpty() || SupertonicTTS.isCancelled()) return ByteArray(0)
            require(prepared.length <= 1200) { "Silero sentence is too long" }
            val voiceFile = File(voice)
            require(voiceFile.canonicalFile.parentFile == root.canonicalFile)
            val speaker = JSONObject(voiceFile.readText()).getInt("speaker")
            require(speakers.keys().asSequence().any { speakers.getInt(it) == speaker })
            load()
            val seq = longArrayOf(symbolIds.getLong(metadata.getString("sos"))) +
                prepared.map { symbolIds.getLong(it.toString()) }.toLongArray() +
                longArrayOf(symbolIds.getLong(metadata.getString("eos")))
            val n = seq.size.toLong()
            val shape = longArrayOf(1, n)
            val rates = FloatArray(seq.size) { speed.coerceIn(.5f, 2.5f) }
            val pitches = FloatArray(seq.size) { 1f }
            val types = SileroText.typeIds(prepared, prefs.getBoolean("silero_intonation", true))
            val t = android.os.SystemClock.elapsedRealtime()
            val pauses = prefs.getBoolean("silero_fixed_pauses",false)
            val commaMs = prefs.getInt("tera_comma_pause_ms",180).coerceIn(0,400)
            val durations = prepared.mapIndexedNotNull { index, c ->
                if (pauses) SileroPauseFrames.forPunctuation(c,commaMs)?.let { (index+1).toLong() to IValue.from(it) } else null
            }.toMap().toMutableMap()
            // A synthetic terminal dot protects the last vowel without adding
            // a new sentence pause (the model's natural prefix is ten frames).
            if (input.addedEndDot) durations[prepared.length.toLong()] = IValue.from(1L)
            val args = arrayListOf(
                IValue.from(Tensor.fromBlob(seq, shape)),
                IValue.from(Tensor.fromBlob(longArrayOf(speaker.toLong()), longArrayOf(1))),
                IValue.from(48000L), if (durations.isEmpty()) IValue.optionalNull() else IValue.dictLongKeyFrom(durations),
                IValue.from(Tensor.fromBlob(rates, shape)), IValue.from(Tensor.fromBlob(pitches, shape)),
                IValue.optionalNull(), IValue.optionalNull(), IValue.from("cpu"), IValue.from(-1L), IValue.from(false)
            )
            if (nativeTypes) {
                args += IValue.from(Tensor.fromBlob(types, shape))
                args += IValue.optionalNull()
            }
            val melResult = mel!!.forward(*args.toTypedArray()).toTuple()
            val out = melResult[0].toTensor()
            if(durations.isNotEmpty()) {
                val actual=melResult[1].toTensor().dataAsFloatArray
                Log.i("SileroTTS","Punctuation frames: " + durations.keys.joinToString { index ->
                    "$index=${actual.getOrNull(index.toInt())}"
                })
            }
            if (SupertonicTTS.isCancelled()) return ByteArray(0)
            val hidden = backbone!!.forward(EValue.from(org.pytorch.executorch.Tensor.fromBlob(out.dataAsFloatArray, out.shape())))[0].toTensor()
            if (SupertonicTTS.isCancelled()) return ByteArray(0)
            val samples = head!!.forward(IValue.from(Tensor.fromBlob(hidden.dataAsFloatArray, hidden.shape())),
                IValue.from(48000L), IValue.from(0.0), IValue.from(true)).toTensor().dataAsFloatArray
            com.brahmadeo.supertonic.tts.utils.SpeechTail.inspect(samples, 48000).takeIf { it.abrupt }?.let {
                Log.w("SileroTTS", "Abrupt phrase end trailingMs=${it.trailingMs} tailRatio=${"%.2f".format(it.tailRatio)} chars=${prepared.length} endCorrection=${prepared != source} source=${com.brahmadeo.supertonic.tts.llm.SpeechTextTrace.fingerprint(text)}")
            }
            val level = com.brahmadeo.supertonic.tts.utils.SpeechLoudness.scale(listOf(samples), gain,
                prefs.getBoolean("voice_loudness_normalization", true))
            val base = com.brahmadeo.supertonic.tts.utils.SpeechLoudness.pcm(samples, level)
            val endPause = if (pauses && source.trimEnd().lastOrNull() in listOf('.', '!', '?', '…')) prefs.getInt("tera_sentence_pause_ms",420).coerceIn(0,900) else 0
            val missing = com.brahmadeo.supertonic.tts.tera.TeraPunctuationPauses.missingSilenceSamples(base,endPause,48000)
            val bytes = if (missing > 0) base + ByteArray(missing*2) else base
            if (SupertonicTTS.isCancelled()) return ByteArray(0)
            // Stream bounded PCM pieces into the existing reader/playback buffer.
            var pos = 0
            while (pos < bytes.size && !SupertonicTTS.isCancelled()) {
                val end = minOf(pos + 48000, bytes.size)
                listener?.onAudioChunk(sid, bytes.copyOfRange(pos, end)); pos = end
            }
            com.brahmadeo.supertonic.tts.utils.DiagLog.i("SileroTTS", "Synthesized chars=${prepared.length} accents=${prepared.count { it == '+' }} endCorrection=${prepared != source} speaker=$speaker types=${types.toSet()} ms=${android.os.SystemClock.elapsedRealtime()-t} audioMs=${samples.size*1000L/48000}")
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
