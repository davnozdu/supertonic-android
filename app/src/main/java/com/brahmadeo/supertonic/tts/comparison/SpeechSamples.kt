package com.brahmadeo.supertonic.tts.comparison

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.brahmadeo.supertonic.tts.SupertonicTTS
import com.brahmadeo.supertonic.tts.llm.*
import com.brahmadeo.supertonic.tts.music.MusicCatalog
import com.brahmadeo.supertonic.tts.music.MusicFiles
import com.brahmadeo.supertonic.tts.utils.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Private diagnostic export: production LLM -> Android TTS -> PCM, music mixed on-device. */
internal object SpeechSamples {
    suspend fun export(ctx: Context) {
        val source = File(ctx.cacheDir,"comparison-source.txt").readText().trim()
        require(source.length in 400..3500)
        val prefs = ctx.getSharedPreferences("SupertonicPrefs",0)
        val llm = ctx.getSharedPreferences("llm_settings",0)
        check(LlmSettings.enabled(ctx)) { "Enable LLM for the requested comparison" }
        val models = listOf(AssetManager.SILERO_MODEL,AssetManager.TERA_MODEL,AssetManager.KOKORO_MODEL,AssetManager.POCKET_MODEL)
        val voices = listOf(listOf("eugene","aidar","kseniya"),listOf("ru_m5","ru_m1","ru_f2"),listOf("sveta","dima","masha"),listOf("jean","marius","eponine"))
        val names = listOf("01-Silero","02-Tera","03-Kokoro-full","04-Shtorm")
        val keys = listOf("selected_model","selected_voice","selected_lang","kokoro_full_precision") + models.flatMap { m -> VoiceRole.entries.map { "multi_voice_${m}_${it.name.lowercase()}" } }
        val saved = keys.associateWith { prefs.all[it] }
        val oldMulti = llm.getBoolean("multi_voice",false)
        val track = prefs.getString("background_music_track","").orEmpty()
        val music = if (track.startsWith("ready:")) MusicCatalog.selected(ctx,track.removePrefix("ready:")) else MusicFiles.custom(ctx)
        check(music != null && music.isFile) { "Selected music is not installed" }
        val gain = prefs.getInt("background_music_volume",10).coerceIn(0,100)/100f
        val directory = File(ctx.cacheDir,"comparison-exports").apply { mkdirs() }
        val report = JSONArray()
        llm.edit().putBoolean("multi_voice",true).commit()
        try {
            for ((index,model) in models.withIndex()) {
                val chosen = voices[index]
                val editor = prefs.edit().putString("selected_model",model).putString("selected_lang","ru").putString("selected_voice",chosen[0]+".json")
                if (model == AssetManager.KOKORO_MODEL) editor.putBoolean("kokoro_full_precision",true)
                VoiceRole.entries.forEachIndexed { i,role -> editor.putString("multi_voice_${model}_${role.name.lowercase()}",chosen[i]) }
                editor.commit()
                delay(350) // Preference callbacks invalidate old-model preparations before submitting.
                SupertonicTTS.release(); SupertonicTTS.setApplicationContext(ctx)
                check(AssetManager.isReady(ctx)) { "Missing model: $model" }
                if (model == AssetManager.KOKORO_MODEL) check(com.brahmadeo.supertonic.tts.kokoro.KokoroDownload.fullReady(ctx))
                check(chosen.all { it in AssetManager.russianVoices(ctx) })
                val id = LlmPreparation.submit(ctx, this, source)
                val prepared = LlmPreparation.prepareResult(ctx,source,id,90000,retainForPlayback=true)
                check(!prepared.fallback && prepared.rolesReady) { "LLM did not fully prepare $model: ${prepared.reason}" }
                check(prepared.voicePlan.map { it.role }.toSet().containsAll(VoiceRole.entries)) { "LLM missed a dialogue role" }
                val ready = CompletableFuture<Int>()
                var client: TextToSpeech? = null
                val speech = File(directory,names[index]+"-voice.wav")
                try {
                    withContext(Dispatchers.Main) { client = TextToSpeech(ctx,{ ready.complete(it) },ctx.packageName) }
                    check(ready.get(20,TimeUnit.SECONDS)==TextToSpeech.SUCCESS)
                    check(client!!.setLanguage(Locale("ru"))>=TextToSpeech.LANG_AVAILABLE)
                    check(client!!.setSpeechRate(1.0f)==TextToSpeech.SUCCESS)
                    check(client!!.setVoice(VoicePreview.voice(chosen[0]))==TextToSpeech.SUCCESS)
                    val done = CompletableFuture<Unit>()
                    client!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { done.complete(Unit) }
                        override fun onError(id: String?) { done.completeExceptionally(IllegalStateException("Sample synthesis failed")) }
                    })
                    val started = SystemClock.elapsedRealtime()
                    check(client!!.synthesizeToFile(source,Bundle(),speech,"comparison-$model")==TextToSpeech.SUCCESS)
                    done.get(180,TimeUnit.SECONDS)
                    val wav = readWave(speech)
                    val output = File(directory,names[index]+"-music.wav")
                    val decoded = decodeMusic(music,wav.samples.size.toDouble()/wav.rate + 1)
                    val mix = ByteBuffer.allocate(wav.samples.size*4).order(ByteOrder.LITTLE_ENDIAN)
                    var clipped = 0
                    for (frame in wav.samples.indices) {
                        val position = frame.toDouble()*decoded.rate/wav.rate
                        val left = position.toLong()%decoded.frames
                        val right = (left+1)%decoded.frames
                        val alpha = (position-position.toLong()).toFloat()
                        for (channel in 0..1) {
                            val ch = channel.coerceAtMost(decoded.channels-1)
                            val a = decoded.samples[(left*decoded.channels+ch).toInt()]
                            val b = decoded.samples[(right*decoded.channels+ch).toInt()]
                            val value = (wav.samples[frame] + (a+(b-a)*alpha)*gain).roundToInt()
                            if (value !in -32768..32767) clipped++
                            mix.putShort(value.coerceIn(-32768,32767).toShort())
                        }
                    }
                    writeWave(output,wav.rate,2,mix.array())
                    File(directory,names[index]+"-prepared.txt").writeText(prepared.text)
                    val row = JSONObject().put("model",model).put("file",output.name).put("voices",JSONArray(chosen))
                        .put("sourceHash",SpeechTextTrace.fingerprint(source)).put("preparedHash",SpeechTextTrace.fingerprint(prepared.text))
                        .put("provider",prepared.provider).put("roleProvider",prepared.roleProvider).put("roles",JSONArray(prepared.voicePlan.map { it.role.name }.distinct()))
                        .put("durationMs",wav.samples.size*1000L/wav.rate).put("rate",wav.rate).put("speed",1.0)
                        .put("wallMs",SystemClock.elapsedRealtime()-started).put("music",track).put("musicGain",gain.toDouble()).put("clippedSamples",clipped)
                    report.put(row)
                    File(directory,"report.json").writeText(report.toString(2))
                    Log.i("SpeechCheck","COMPARISON SAMPLE $row")
                } finally { client?.stop(); client?.shutdown(); LlmPreparation.cancel(this) }
            }
            Log.i("SpeechCheck","COMPARISON EXPORT PASSED count=${report.length()} source=${SpeechTextTrace.fingerprint(source)}")
        } finally {
            val editor = prefs.edit()
            saved.forEach { (key,value) -> when(value) {
                null -> editor.remove(key)
                is String -> editor.putString(key,value)
                is Boolean -> editor.putBoolean(key,value)
            } }
            editor.commit()
            llm.edit().putBoolean("multi_voice",oldMulti).commit()
            SupertonicTTS.release()
            LlmPreparation.settingsChanged()
            Log.i("SpeechCheck","Comparison settings restored")
        }
    }
    private data class Wave(val rate: Int,val samples: ShortArray)
    private fun readWave(file: File): Wave {
        val bytes = file.readBytes(); val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(String(bytes,0,4,Charsets.US_ASCII)=="RIFF" && String(bytes,8,4,Charsets.US_ASCII)=="WAVE")
        var position=12; var rate=0; var channels=0; var bits=0
        while(position+8<=bytes.size) {
            val size=buffer.getInt(position+4)
            check(size>=0 && position.toLong()+8+size<=bytes.size)
            when(String(bytes,position,4,Charsets.US_ASCII)) {
                "fmt " -> { check(buffer.getShort(position+8).toInt()==1); channels=buffer.getShort(position+10).toInt();rate=buffer.getInt(position+12);bits=buffer.getShort(position+22).toInt() }
                "data" -> {
                    check(channels==1 && bits==16 && rate in 8000..96000 && size%2==0)
                    return Wave(rate,ShortArray(size/2) { buffer.getShort(position+8+it*2) })
                }
            }
            position+=8+size+size%2
        }
        error("No PCM in sample")
    }
    private data class Music(val rate: Int,val channels: Int,val samples: ShortArray) { val frames get()=samples.size/channels }
    private fun decodeMusic(file: File, seconds: Double): Music {
        val extractor=MediaExtractor();var codec: MediaCodec?=null
        try {
            extractor.setDataSource(file.path)
            val index=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true }
            extractor.selectTrack(index)
            val format=extractor.getTrackFormat(index)
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!);codec=decoder
            decoder.configure(format,null,null,0);decoder.start()
            val output=ByteArrayOutputStream();val info=MediaCodec.BufferInfo();var inputDone=false;var outputDone=false
            val deadline=SystemClock.elapsedRealtime()+30000
            while(!outputDone && output.size()<seconds*rate*channels*2) {
                check(SystemClock.elapsedRealtime()<deadline) { "Music decoding timed out" }
                if(!inputDone) {
                    val slot=decoder.dequeueInputBuffer(10000)
                    if(slot>=0) {
                        val input=decoder.getInputBuffer(slot)!!.apply { clear() }
                        val size=extractor.readSampleData(input,0)
                        if(size<0) { decoder.queueInputBuffer(slot,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true }
                        else { decoder.queueInputBuffer(slot,0,size,extractor.sampleTime,0);extractor.advance() }
                    }
                }
                val slot=decoder.dequeueOutputBuffer(info,10000)
                if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val actual=decoder.outputFormat;rate=actual.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    check(!actual.containsKey(MediaFormat.KEY_PCM_ENCODING) || actual.getInteger(MediaFormat.KEY_PCM_ENCODING)==AudioFormat.ENCODING_PCM_16BIT)
                    check(channels in 1..2 && rate in 8000..96000)
                } else if(slot>=0) {
                    val data=decoder.getOutputBuffer(slot)!!.apply { position(info.offset);limit(info.offset+info.size) }
                    val bytes=ByteArray(info.size);data.get(bytes);output.write(bytes)
                    check(output.size()<=64*1024*1024)
                    outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(slot,false)
                }
            }
            val data=ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            val samples=ShortArray(data.remaining()/2) { data.short }
            check(samples.size>=channels && samples.size%channels==0)
            return Music(rate,channels,samples)
        } finally { runCatching { codec?.stop() };codec?.release();extractor.release() }
    }
    private fun writeWave(file: File, rate: Int, channels: Int, pcm: ByteArray) {
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(pcm.size+36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate*channels*2).putShort((channels*2).toShort()).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size)
        file.outputStream().use { it.write(header.array());it.write(pcm) }
    }
}
