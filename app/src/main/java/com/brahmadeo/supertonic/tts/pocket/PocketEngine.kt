package com.brahmadeo.supertonic.tts.pocket

import android.content.Context
import android.util.Log
import com.brahmadeo.supertonic.tts.SupertonicTTS
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PocketEngine(context: Context) : AutoCloseable {
    private val root=PocketDownload.root(context)
    private var native: NativePocketTts?=null
    private var used=android.os.SystemClock.elapsedRealtime()
    private val idle=Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable,"PocketIdle").apply { isDaemon=true } }
    init {
        require(PocketDownload.supported())
        idle.scheduleWithFixedDelay({ synchronized(this) {
            if(native!=null && android.os.SystemClock.elapsedRealtime()-used>120000) unload()
        } },15,15,TimeUnit.SECONDS)
    }
    @Synchronized fun synthesize(text: String, voiceFile: String, speed: Float, gain: Float, listener: SupertonicTTS.ProgressListener?, sid: Long): ByteArray {
        used=android.os.SystemClock.elapsedRealtime()
        try {
            val engine=native ?: NativePocketTts(root.path,root.path,"fp32",.3f,1,
                Runtime.getRuntime().availableProcessors().coerceIn(1,4),180,50).also { native=it }
            val output=ByteArrayOutputStream()
            val start=android.os.SystemClock.elapsedRealtime()
            for(chunk in PocketText.chunks(text)) {
                if(SupertonicTTS.isCancelled()) return ByteArray(0)
                val frames=ArrayList<FloatArray>()
                var count=0
                var peak=0f
                val okay=engine.synthesize(PocketText.modelPrompt(chunk),voiceFile,speed.coerceIn(.5f,2.5f),object: NativePocketTts.AudioSink {
                    override fun onAudio(samples: FloatArray): Boolean {
                        if(SupertonicTTS.isCancelled()) return false
                        check(samples.all { it.isFinite() }) { "PocketTTS produced invalid audio" }
                        check(output.size().toLong()+(count.toLong()+samples.size)*2L<=64L*1024*1024) { "PocketTTS output exceeds limit" }
                        count+=samples.size
                        for(sample in samples) peak=maxOf(peak,kotlin.math.abs(sample))
                        frames.add(samples)
                        return !SupertonicTTS.isCancelled()
                    }
                })
                if(!okay || SupertonicTTS.isCancelled()) return ByteArray(0)
                // Normalize a bounded phrase consistently; never hard-clip the common 2.5x gain.
                val safeGain=if(peak>0f) minOf(gain.coerceAtLeast(0f),.98f/peak) else 1f
                val phrase = ByteArrayOutputStream(count * 2)
                for(samples in frames) {
                    if(SupertonicTTS.isCancelled()) return ByteArray(0)
                    val pcm=ByteBuffer.allocate(samples.size*2).order(ByteOrder.LITTLE_ENDIAN)
                    samples.forEach { pcm.putShort((it*safeGain*32767).toInt().coerceIn(-32768,32767).toShort()) }
                    val bytes=pcm.array()
                    phrase.write(bytes)
                    output.write(bytes);listener?.onAudioChunk(sid,bytes)
                }
                if (chunk.trimEnd('"', '\'', '»', ')', ']').lastOrNull() in listOf('.', '!', '?', '…')) {
                    val missing = com.brahmadeo.supertonic.tts.tera.TeraPunctuationPauses.missingSilenceSamples(phrase.toByteArray(), 180, 24000)
                    if (missing > 0) {
                        val silence = ByteArray(missing * 2)
                        output.write(silence); listener?.onAudioChunk(sid, silence)
                    }
                }
            }
            Log.i("PocketTTS","Synthesized chars=${text.length} accents=${PocketText.prepare(text).count { it=='\u0301' }} ms=${android.os.SystemClock.elapsedRealtime()-start} audioMs=${output.size()*1000L/48000}")
            return output.toByteArray()
        } finally { used=android.os.SystemClock.elapsedRealtime() }
    }
    private fun unload() { native?.close();native=null;Log.i("PocketTTS","Runtime released from RAM") }
    @Synchronized override fun close() { idle.shutdownNow();unload() }
}
