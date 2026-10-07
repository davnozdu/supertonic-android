package com.brahmadeo.supertonic.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.brahmadeo.supertonic.tts.utils.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Non-exported, root/ADB-only integration check. Synthetic text, no playback. */
class SpeechDiagnosticsActivity : ComponentActivity() {
    companion object { private val running = java.util.concurrent.atomic.AtomicBoolean() }
    private fun findPcmStart(bytes: ByteArray): Int {
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val size = ByteBuffer.wrap(bytes,pos+4,4).order(ByteOrder.LITTLE_ENDIAN).int
            require(size >= 0 && pos.toLong() + 8 + size <= bytes.size)
            if (String(bytes,pos,4,Charsets.US_ASCII) == "data") return pos + 8
            pos += 8 + size + size % 2
        }
        error("Missing WAV PCM")
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        com.brahmadeo.supertonic.tts.utils.DiagLog.verbose = true
        if (!running.compareAndSet(false,true)) {
            Log.w("SpeechCheck","Diagnostic already running; duplicate ignored")
            finish()
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Read-only NPU benchmark: never touches model, voice or reading settings.
                if (intent.getBooleanExtra("teraNpuProbe", false)) {
                    try { com.brahmadeo.supertonic.tts.kokoro.NpuProbe.teraHybrid(this@SpeechDiagnosticsActivity) } finally { running.set(false) }
                    return@withContext
                }
                if (intent.getBooleanExtra("kokoroNpuProbe", false)) {
                    try { com.brahmadeo.supertonic.tts.kokoro.NpuProbe.kokoroKit(this@SpeechDiagnosticsActivity, intent.getStringExtra("kokoroNpuModel") ?: "model.onnx") } finally { running.set(false) }
                    return@withContext
                }
                if (intent.getBooleanExtra("stressProbe", false)) {
                    try { com.brahmadeo.supertonic.tts.llm.StressProbe.run(this@SpeechDiagnosticsActivity, intent.getStringExtra("provider"),
                        if (intent.hasExtra("verifyThinking")) intent.getBooleanExtra("verifyThinking", false) else null,
                        intent.getStringExtra("verifier")) }
                    catch (e: Exception) { Log.e("SpeechCheck", "STRESS PROBE FAILED ${e.javaClass.simpleName}: ${e.message}") }
                    finally { running.set(false) }
                    return@withContext
                }
                if (intent.getBooleanExtra("npuProbe", false)) {
                    try { com.brahmadeo.supertonic.tts.kokoro.NpuProbe.run(this@SpeechDiagnosticsActivity) } finally { running.set(false) }
                    return@withContext
                }
                val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
                val oldModel = AssetManager.getModelType(this@SpeechDiagnosticsActivity)
                val oldVoice = prefs.getString("selected_voice", "F3.json")
                val llmPrefs=getSharedPreferences("llm_settings",MODE_PRIVATE)
                val oldLlmMode=llmPrefs.getString("mode","OFF")
                val oldMultiVoice=llmPrefs.getBoolean("multi_voice",false)
                val multiVoiceProbe=intent.getBooleanExtra("multiVoiceProbe",false) || intent.getBooleanExtra("roleRecoveryProbe",false)
                val voicePreviewProbe=intent.getBooleanExtra("voicePreviewProbe",false)
                val oldRestoreYo=llmPrefs.getBoolean("restore_yo",true)
                val offline=intent.getBooleanExtra("offline",false)
                val oldLocalStress=prefs.getBoolean("local_russian_stress",true)
                val verifyStress=intent.getBooleanExtra("stress",false)
                val oldLang = prefs.getString("selected_lang", "en")
                val model = intent.getStringExtra("model") ?: AssetManager.SILERO_MODEL
                var tts: TextToSpeech? = null
                try {
                    if(multiVoiceProbe) {
                        val requested = intent.getStringExtra("roleProvider") ?: oldLlmMode
                        require(requested in listOf("LOCAL", "GEMINI", "OLLAMA", "AUTO"))
                        llmPrefs.edit().putBoolean("multi_voice",true).putString("mode",requested).commit()
                        com.brahmadeo.supertonic.tts.llm.LlmPreparation.settingsChanged()
                    }
                    if(voicePreviewProbe) {
                        llmPrefs.edit().putBoolean("multi_voice",true).commit()
                        com.brahmadeo.supertonic.tts.llm.LlmPreparation.settingsChanged()
                    }
                    if(verifyStress) prefs.edit().putBoolean("local_russian_stress",true).commit()
                    if(offline) {
                        llmPrefs.edit().putString("mode","OFF").commit()
                        com.brahmadeo.supertonic.tts.llm.LlmPreparation.settingsChanged()
                    }
                    require(model in setOf(AssetManager.SILERO_MODEL, AssetManager.SILERO_CIS_MODEL, AssetManager.TERA_MODEL, AssetManager.POCKET_MODEL, AssetManager.KOKORO_MODEL,"standard","android_optimized_int8","android_optimized_fp16","android_optimized_fp32"))
                    AssetManager.setModelType(this@SpeechDiagnosticsActivity, model)
                    prefs.edit().putString("selected_lang", "ru")
                        .putString("selected_voice", when(model) { AssetManager.KOKORO_MODEL -> "sveta.json"; AssetManager.POCKET_MODEL -> "alba.json"; AssetManager.SILERO_CIS_MODEL -> "ru_alexandr.json"; AssetManager.SILERO_MODEL -> "kseniya.json"; AssetManager.TERA_MODEL -> "ru_f1.json"; else -> "F3.json" }).apply()
                    SupertonicTTS.release()
                    if (intent.getBooleanExtra("teacherProbe", false)) {
                        check(model == AssetManager.TERA_MODEL)
                        val root=File(filesDir,"${AssetManager.MODEL_VERSION}/tera")
                        val style=AssetManager.voiceFile(this@SpeechDiagnosticsActivity,"ru_f1.json").path
                        val sample="Это не раз было. По-прежнему светло. Ты готов? Да, всё хорошо!"
                        SupertonicTTS.setCancelled(false)
                        if (intent.getBooleanExtra("withGemma", false)) {
                            val result=com.brahmadeo.supertonic.tts.llm.LlmPreparation.test(this@SpeechDiagnosticsActivity,
                                com.brahmadeo.supertonic.tts.llm.LlmSettings.load(this@SpeechDiagnosticsActivity)
                                    .copy(mode=com.brahmadeo.supertonic.tts.llm.LlmMode.LOCAL),sample,traceSynthetic=true)
                            Log.i("SpeechCheck","BENCH Gemma provider=${result.provider} fallback=${result.fallback} ms=${result.elapsedMs}")
                        }
                        for (sampler in listOf("sampler_distilled_cfg3_8step","sampler_teacher_8step")) {
                            val loadStart=android.os.SystemClock.elapsedRealtime()
                            com.brahmadeo.supertonic.tts.tera.TeraEngine(root,this@SpeechDiagnosticsActivity,sampler).use { engine ->
                                Log.i("SpeechCheck","BENCH sampler=$sampler loadMs=${android.os.SystemClock.elapsedRealtime()-loadStart}")
                                repeat(2) { pass ->
                                    val started=android.os.SystemClock.elapsedRealtime()
                                    val pcm=engine.synthesize(sample,"ru",style,1.1f,2.5f,null,0)
                                    val elapsed=android.os.SystemClock.elapsedRealtime()-started
                                    check(pcm.isNotEmpty())
                                    val memory=android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
                                    Log.i("SpeechCheck","BENCH sampler=$sampler pass=$pass ms=$elapsed audioMs=${pcm.size*1000L/88200} pssKb=${memory.totalPss}")
                                }
                            }
                        }
                        return@withContext
                    }
                    if (!AssetManager.isReady(this@SpeechDiagnosticsActivity)) {
                        AssetManager.download(this@SpeechDiagnosticsActivity) { _, progress ->
                            Log.i("SpeechCheck", "Download progress=${(progress*100).toInt()}")
                        }
                    }
                    if (intent.getBooleanExtra("threadsProbe", false)) {
                        val ctx = this@SpeechDiagnosticsActivity
                        SupertonicTTS.setApplicationContext(ctx)
                        val key = com.brahmadeo.supertonic.tts.utils.EngineThreadPolicy.key(model)
                        val saved = if (prefs.contains(key)) prefs.getInt(key, 4) else null
                        val voice = prefs.getString("selected_voice", "sveta.json")!!
                        val style = AssetManager.voiceFile(ctx, voice).path
                        val sample = com.brahmadeo.supertonic.tts.llm.VoicePreview.SAMPLE
                        try {
                            for (threads in listOf(2, 4, 6).filter { it <= com.brahmadeo.supertonic.tts.utils.EngineThreads.maximum }) {
                                com.brahmadeo.supertonic.tts.utils.EngineThreads.save(ctx, model, threads)
                                delay(150)
                                check(com.brahmadeo.supertonic.tts.utils.EngineThreads.selected(ctx) == threads)
                                SupertonicTTS.setCancelled(false)
                                // Cold pass loads the model; second pass measures inference without a PCM hit.
                                for (pass in 0..1) {
                                    SupertonicTTS.clearAudioCache()
                                    val wall = android.os.SystemClock.elapsedRealtime()
                                    val cpu = android.os.Process.getElapsedCpuTime()
                                    val pcm = SupertonicTTS.generateAudio(sample,"ru",style,1.1f,0f,5,2.5f,skipDictionary=true)
                                    check(pcm != null && pcm.size > 24000) { "No PCM at threads=$threads" }
                                    val elapsed = android.os.SystemClock.elapsedRealtime()-wall
                                    val elapsedCpu = android.os.Process.getElapsedCpuTime()-cpu
                                    val memory = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
                                    Log.i("SpeechCheck","THREAD BENCH model=$model threads=$threads pass=$pass wallMs=$elapsed cpuMs=$elapsedCpu audioMs=${pcm.size*1000L/(SupertonicTTS.getAudioSampleRate()*2)} pssKb=${memory.totalPss}")
                                }
                            }
                            Log.i("SpeechCheck","THREAD PROBE PASSED model=$model; settings reached inference workers")
                        } finally {
                            if (saved == null) prefs.edit().remove(key).commit() else prefs.edit().putInt(key,saved).commit()
                        }
                        return@withContext
                    }
                    if(intent.getBooleanExtra("kokoroBenchmark",false)) {
                        check(model==AssetManager.KOKORO_MODEL)
                        com.brahmadeo.supertonic.tts.kokoro.KokoroDownload.downloadFull(this@SpeechDiagnosticsActivity) { status,_ ->
                            Log.i("SpeechCheck",status)
                        }
                        val ctx=this@SpeechDiagnosticsActivity
                        val text=com.brahmadeo.supertonic.tts.llm.VoicePreview.SAMPLE
                        val baseline=com.brahmadeo.supertonic.tts.kokoro.KokoroEngine(ctx,false,4)
                        baseline.use { engine ->
                            SupertonicTTS.setCancelled(false)
                            for(voice in listOf("sveta","dima")) {
                                for(pass in 0..1) {
                                    val wall=android.os.SystemClock.elapsedRealtime();val cpu=android.os.Process.getElapsedCpuTime()
                                    val pcm=engine.synthesize(text,com.brahmadeo.supertonic.tts.kokoro.KokoroDownload.voiceFile(ctx,voice).path,1.1f,2.5f,null,0)
                                    check(pcm.isNotEmpty())
                                    val memory=android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
                                    Log.i("SpeechCheck","KOKORO BENCH full=false threads=4 voice=$voice pass=$pass wallMs=${android.os.SystemClock.elapsedRealtime()-wall} cpuMs=${android.os.Process.getElapsedCpuTime()-cpu} audioMs=${pcm.size*1000L/48000} pssKb=${memory.totalPss}")
                                }
                            }
                        }
                        for(threads in listOf(2,4,6)) {
                            com.brahmadeo.supertonic.tts.kokoro.KokoroEngine(ctx,true,threads).use { engine ->
                                for(voice in listOf("sveta","dima")) for(pass in 0..1) {
                                    val wall=android.os.SystemClock.elapsedRealtime();val cpu=android.os.Process.getElapsedCpuTime()
                                    val pcm=engine.synthesize(text,com.brahmadeo.supertonic.tts.kokoro.KokoroDownload.voiceFile(ctx,voice).path,1.1f,2.5f,null,0)
                                    check(pcm.isNotEmpty())
                                    val memory=android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
                                    Log.i("SpeechCheck","KOKORO BENCH full=true threads=$threads voice=$voice pass=$pass wallMs=${android.os.SystemClock.elapsedRealtime()-wall} cpuMs=${android.os.Process.getElapsedCpuTime()-cpu} audioMs=${pcm.size*1000L/48000} pssKb=${memory.totalPss}")
                                }
                            }
                        }
                        Log.i("SpeechCheck","KOKORO BENCH PASSED; cold and warm CPU/memory samples recorded")
                        return@withContext
                    }
                    if(intent.getBooleanExtra("resourceProbe",false)) {
                        check(model == AssetManager.TERA_MODEL)
                        val root = File(filesDir,"${AssetManager.MODEL_VERSION}/tera")
                        val style = AssetManager.voiceFile(this@SpeechDiagnosticsActivity,"ru_f1.json").path
                        val sample = "Ти́хий ве́чер. За окно́м шелестя́т дере́вья."
                        var reference: ByteArray? = null
                        SupertonicTTS.setCancelled(false)
                        for(spin in listOf(true,false)) {
                            com.brahmadeo.supertonic.tts.tera.TeraEngine(root,this@SpeechDiagnosticsActivity,
                                com.brahmadeo.supertonic.tts.tera.TeraQuality.selected(this@SpeechDiagnosticsActivity),spin).use { engine ->
                                val warmed = engine.synthesize(sample,"ru",style,1.1f,2.5f,null,0,true)
                                check(warmed.isNotEmpty())
                                if(reference == null) reference = warmed else check(reference!!.contentEquals(warmed)) { "Spinning changed PCM" }
                                repeat(3) { pass ->
                                    val wall = android.os.SystemClock.elapsedRealtime()
                                    val cpu = android.os.Process.getElapsedCpuTime()
                                    val pcm = engine.synthesize(sample,"ru",style,1.1f,2.5f,null,0,true)
                                    check(reference!!.contentEquals(pcm)) { "PCM changed during resource benchmark" }
                                    Log.i("SpeechCheck","RESOURCE spin=$spin pass=$pass wallMs=${android.os.SystemClock.elapsedRealtime()-wall} processCpuMs=${android.os.Process.getElapsedCpuTime()-cpu} audioMs=${pcm.size*1000L/88200}")
                                }
                            }
                        }
                        Log.i("SpeechCheck","RESOURCE PROBE PASSED: PCM identical with spinning on/off")
                        return@withContext
                    }
                    if(offline) {
                        val context=this@SpeechDiagnosticsActivity
                        val accented=com.brahmadeo.supertonic.tts.local.LocalRussianStress.apply(context,"секретарем зеленый ребенок кораблем береза веселый костер ковер. Текст письма. Лене. Позвали Семена.")
                        Log.i("SpeechCheck","OFFLINE TEXT: $accented")
                        check(listOf("секретарём","зелёный","ребёнок","кораблём","берёза","весёлый","костёр","ковёр").all { it in accented.replace("+","") })
                        check("письм+а" in accented && "Л+ене" in accented && "Семёна" in accented)
                        check(com.brahmadeo.supertonic.tts.local.LocalRussianStress.apply(context,"све+тло, берёза.")=="све+тло, берёза.")
                        Log.i("SpeechCheck","OFFLINE stress/yo and explicit-stress checks passed; LLM disabled")
                        if(intent.getBooleanExtra("cache",false)) {
                            SupertonicTTS.setApplicationContext(context)
                            val normalizer=com.brahmadeo.supertonic.tts.utils.TextNormalizer()
                            val wall=normalizer.normalize("По стенам.","ru")
                            check("стёнам" !in wall.replace("+","").replace("\u0301","").lowercase())
                            llmPrefs.edit().putBoolean("restore_yo",false).commit()
                            com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
                            val plain=normalizer.normalize("Зеленый ребенок, ковер и берёза.","ru").replace("+","").replace("\u0301","")
                            check("Зеленый ребенок, ковер и берёза." == plain)
                            llmPrefs.edit().putBoolean("restore_yo",oldRestoreYo).commit()
                            com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
                            Log.i("SpeechCheck","YO SWITCH AND WALL REGRESSION PASSED: $wall; disabled=$plain")
                        }
                    }
                    val ready = CompletableFuture<Int>()
                    withContext(Dispatchers.Main) {
                        tts = TextToSpeech(this@SpeechDiagnosticsActivity, { ready.complete(it) }, packageName)
                    }
                    check(ready.get(10, TimeUnit.SECONDS) == TextToSpeech.SUCCESS)
                    check(tts!!.setLanguage(Locale("ru")) >= TextToSpeech.LANG_AVAILABLE)
                    tts!!.setSpeechRate(1.1f)
                    if(voicePreviewProbe) {
                        val voices=AssetManager.russianVoices(this@SpeechDiagnosticsActivity)
                        check(voices.isNotEmpty())
                        for(voice in voices) {
                            check(tts!!.setVoice(com.brahmadeo.supertonic.tts.llm.VoicePreview.voice(voice))==TextToSpeech.SUCCESS)
                            val done=CompletableFuture<Unit>()
                            tts!!.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
                                override fun onStart(id: String?) {}
                                override fun onDone(id: String?) { done.complete(Unit) }
                                override fun onError(id: String?) { done.completeExceptionally(IllegalStateException("Voice preview failed")) }
                            })
                            val output=File(cacheDir,"voice-preview-probe.wav")
                            try {
                                check(tts!!.synthesizeToFile(com.brahmadeo.supertonic.tts.llm.VoicePreview.SAMPLE,
                                    com.brahmadeo.supertonic.tts.llm.VoicePreview.params(this@SpeechDiagnosticsActivity),output,"preview-$voice")==TextToSpeech.SUCCESS)
                                done.get(30,TimeUnit.SECONDS)
                                check(output.length()>44)
                                Log.i("SpeechCheck","VOICE PREVIEW PASSED model=$model voice=$voice wavBytes=${output.length()}")
                            } finally { output.delete() }
                        }
                        Log.i("SpeechCheck","ALL VOICE PREVIEWS PASSED model=$model count=${voices.size}; multivoice enabled during check")
                        return@withContext
                    }
                    if(multiVoiceProbe) {
                        val sample="Павел подошёл к окну.\n— Как красиво! — сказала Ольга.\n— Да, сегодня прекрасный день, — ответил Павел."
                        val config = com.brahmadeo.supertonic.tts.llm.LlmSettings.load(this@SpeechDiagnosticsActivity)
                        val result = if (intent.getBooleanExtra("roleRecoveryProbe",false))
                            com.brahmadeo.supertonic.tts.llm.LlmPreparation.testRoleRecovery(this@SpeechDiagnosticsActivity, config, sample).second
                        else com.brahmadeo.supertonic.tts.llm.LlmPreparation.test(this@SpeechDiagnosticsActivity,config,sample)
                        if (config.mode != com.brahmadeo.supertonic.tts.llm.LlmMode.LOCAL) check(result.rolesReady &&
                            result.voicePlan.map { it.role }.toSet().containsAll(com.brahmadeo.supertonic.tts.llm.VoiceRole.entries)) { "Not all three roles were classified" }
                        val routes = com.brahmadeo.supertonic.tts.llm.MultiVoiceSettings.parts(this@SpeechDiagnosticsActivity,result.text,result.voicePlan,AssetManager.voiceFile(this@SpeechDiagnosticsActivity,"kseniya.json").path)
                        if (config.mode != com.brahmadeo.supertonic.tts.llm.LlmMode.LOCAL) check(routes.map { it.second }.distinct().size == 3) { "Choose three distinct role voices" }
                        val roles=result.voicePlan.map { it.role }.toSet()
                        check(com.brahmadeo.supertonic.tts.llm.VoiceRolePlan.safe(result.text,result.voicePlan).joinToString("") { it.text }==result.text)
                        Log.i("SpeechCheck","MULTIVOICE provider=${result.provider} fallback=${result.fallback} roles=$roles ms=${result.elapsedMs}")
                        val done=CompletableFuture<Unit>()
                        tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {}
                            override fun onDone(id: String?) { done.complete(Unit) }
                            override fun onError(id: String?) { done.completeExceptionally(IllegalStateException("Multivoice synthesis failed")) }
                        })
                        val output=File(cacheDir,"multivoice-probe.wav")
                        check(tts!!.synthesizeToFile(sample,Bundle(),output,"multivoice-probe")==TextToSpeech.SUCCESS)
                        done.get(90,TimeUnit.SECONDS)
                        check(output.length()>44)
                        val waveBytes = output.readBytes()
                        val pcmStart = findPcmStart(waveBytes)
                        var squares = 0.0; var count = 0; var peak = 0
                        for (i in pcmStart until waveBytes.size - 1 step 2) {
                            val v = ((waveBytes[i].toInt() and 255) or (waveBytes[i+1].toInt() shl 8)).toShort().toInt()
                            peak = maxOf(peak,kotlin.math.abs(v)); if (kotlin.math.abs(v) > 100) { squares += v.toDouble()*v; count++ }
                        }
                        Log.i("SpeechCheck","VOICE LEVEL peak=$peak activeRms=${if(count>0) kotlin.math.sqrt(squares/count).toInt() else 0} samples=${(waveBytes.size-pcmStart)/2}")
                        Log.i("SpeechCheck","MULTIVOICE PIPELINE PASSED model=$model wavBytes=${output.length()} ${SupertonicTTS.audioCacheStatus()}")
                        return@withContext
                    }
                    val bookFile=File(cacheDir,"book-probe.txt")
                    val bookProbe=intent.getBooleanExtra("bookProbe",false)
                    val cases = if(bookProbe) {
                        require(model==AssetManager.POCKET_MODEL)
                        require(bookFile.length() in 1..20000)
                        bookFile.readText().split("\n\n").filter { it.isNotBlank() }.also { require(it.size in 1..20) }
                    } else listOf(
                        "Это не\u00a0раз\u202fбыло. Да.Нет!Как? По-прежнему светло. В списке 1001 имя и 1101 запись. Да, я готов!",
                        "Это не\u00a0раз\u202fбыло. Да.Нет!Как? По-прежнему светло. В списке 1001 имя и 1101 запись. Да, я готов!",
                        "Он сказал: Hello world! Потом добавил: Dobrý den, jak se máte? Всё хорошо.",
                        "Hello world! There are 1001 names.",
                        "Dobrý den, jak se máte? Máme 1101 záznamů."
                    )
                    SupertonicTTS.setApplicationContext(this@SpeechDiagnosticsActivity)
                    val llmOnly="Она страда́ла. Теперь светло́, елка и все. Ты гото́в?"
                    check(com.brahmadeo.supertonic.tts.utils.TextNormalizer().normalize(llmOnly,"ru",skipStress=true)==llmOnly)
                    Log.i("SpeechCheck","LLM PRIORITY NORMALIZER PASSED: no dictionary stress or yo additions")
                    if(intent.getBooleanExtra("llm",false)) {
                        val result=com.brahmadeo.supertonic.tts.llm.LlmPreparation.test(this@SpeechDiagnosticsActivity,
                            com.brahmadeo.supertonic.tts.llm.LlmSettings.load(this@SpeechDiagnosticsActivity),
                            "По-прежнему светло. Она страдала. Ты готов? Это не раз было.",traceSynthetic=true)
                        Log.i("SpeechCheck","LLM PROVIDER TEST provider=${result.provider} fallback=${result.fallback} ms=${result.elapsedMs} reason=${result.reason}")
                        if(!result.fallback) {
                            check(com.brahmadeo.supertonic.tts.utils.TextNormalizer().normalize(result.text,"ru",skipStress=true)==result.text)
                            Log.i("SpeechCheck","LLM SYNTHETIC RESULT: ${result.text}")
                        }
                    }
                    if(intent.getBooleanExtra("promptProbe",false)) {
                        val config=com.brahmadeo.supertonic.tts.llm.LlmSettings.load(this@SpeechDiagnosticsActivity)
                            .copy(mode=com.brahmadeo.supertonic.tts.llm.LlmMode.LOCAL,gpu=intent.getStringExtra("backend")!="cpu",localThinking=false)
                        val sample="По-прежнему светло. Она страдала. Ты готов? Это не раз было."
                        val styles=listOf("caps" to null,"acute" to null,
                            "caps" to "Расставь ударения во всех русских словах. Обозначь ударную гласную заглавной буквой: водА, дорогА, молокО. Не меняй слова. Ответ — только текст с ударениями.",
                            "acute" to "Расставь ударения во всех русских словах: вода́, доро́га, молоко́. Не меняй слова. Ответ — только текст с ударениями.")
                        for((index,style) in styles.withIndex()) {
                            val started=android.os.SystemClock.elapsedRealtime()
                            val answer=com.brahmadeo.supertonic.tts.llm.LlmProviders.local(this@SpeechDiagnosticsActivity,config,listOf(sample),
                                protocol=style.first,diagnosticInstruction=style.second).single()
                            var reason=""
                            val valid=com.brahmadeo.supertonic.tts.llm.PreparedTextValidator.validate(sample,answer) { reason=it }
                            Log.i("SpeechCheck","PROMPT PROBE style=$index protocol=${style.first} ms=${android.os.SystemClock.elapsedRealtime()-started} valid=${valid!=null} reason=$reason; result=$answer")
                        }
                        return@withContext
                    }
                    if(intent.getBooleanExtra("gemmaProbe",false)) {
                        val backend=intent.getStringExtra("backend") ?: "gpu"
                        val config=com.brahmadeo.supertonic.tts.llm.LlmSettings.load(this@SpeechDiagnosticsActivity)
                            .copy(mode=com.brahmadeo.supertonic.tts.llm.LlmMode.LOCAL,gpu=backend=="gpu",localThinking=false)
                        val samples=listOf("По-прежнему светло. Она страдала. Ты готов? Это не раз было.",
                            "Когда ветер стих мы открыли окно. Всё хорошо!",
                            "В списке 1001 имя и 1101 запись.",
                            "Зеленый ребенок сидит под елкой. Все ученики пришли, и теперь все готово.")
                        var passed=true
                        for((index,sample) in samples.withIndex()) {
                            val result=com.brahmadeo.supertonic.tts.llm.LlmPreparation.test(this@SpeechDiagnosticsActivity,config,sample,traceSynthetic=true)
                            Log.i("SpeechCheck","GEMMA PROBE backend=$backend case=$index provider=${result.provider} fallback=${result.fallback} ms=${result.elapsedMs} reason=${result.reason}; result=${result.text}")
                            val plain=result.text.replace("\u0301", "")
                            val correct=!result.fallback && when(index) {
                                0 -> listOf("светло́","страда́ла","гото́в").all { it in result.text }
                                3 -> listOf("Зелёный","ребёнок","ёлкой","Все ученики","всё готово").all { it in plain }
                                else -> true
                            }
                            passed=passed && correct
                            Log.i("SpeechCheck","GEMMA QUALITY case=$index correct=$correct")
                        }
                        if(passed) Log.i("SpeechCheck","GEMMA PROBE PASSED backend=$backend")
                        else Log.w("SpeechCheck","GEMMA PROBE COMPLETED backend=$backend: some quality checks failed; safe fallback remains active")
                        return@withContext
                    }
                    val words=com.brahmadeo.supertonic.tts.utils.TextNormalizer().normalize("Не\u00a0раз\u202fбыло.","ru")
                        .replace("+","").replace("\u0301","")
                    check(words=="Не раз было.")
                    Log.i("SpeechCheck","WORD BOUNDARIES PASSED model=$model: $words")
                    if(bookProbe && intent.getBooleanExtra("tailProbe",false)) {
                        val root=com.brahmadeo.supertonic.tts.pocket.PocketDownload.root(this@SpeechDiagnosticsActivity)
                        val normalizer=com.brahmadeo.supertonic.tts.utils.TextNormalizer()
                        val prepared=cases.map { text ->
                            val result=com.brahmadeo.supertonic.tts.llm.LlmPreparation.prepareResult(this@SpeechDiagnosticsActivity,text,timeoutMs=60000)
                            normalizer.normalize(result.text,"ru",skipStress=!result.fallback).also {
                                Log.i("SpeechCheck","BOOK INPUT llm=${!result.fallback}: $it")
                            }
                        }
                        val decodeSteps=intent.getIntExtra("decodeSteps",1).coerceIn(1,4)
                        val selectedProbeVoice=intent.getStringExtra("probeVoice") ?: "alba"
                        require(selectedProbeVoice in com.brahmadeo.supertonic.tts.pocket.PocketVoices.names)
                        val probeVoice=if(intent.getBooleanExtra("legacyVoice",false)) File(root,"alba.wav") else
                            com.brahmadeo.supertonic.tts.pocket.PocketVoices.voiceFile(this@SpeechDiagnosticsActivity,selectedProbeVoice+".json")
                        val seed=intent.getLongExtra("seed",42)
                        for(tail in if(intent.getBooleanExtra("compareTail",false)) listOf(-2,-1) else listOf(-1)) {
                            com.brahmadeo.supertonic.tts.pocket.NativePocketTts(root.path,root.path,"fp32",.3f,decodeSteps,4,180,50,tail).use { engine ->
                                for((index,text) in prepared.withIndex()) {
                                    val audio=java.io.ByteArrayOutputStream()
                                    for((part,chunk) in com.brahmadeo.supertonic.tts.pocket.PocketText.chunks(text).withIndex()) {
                                        check(engine.synthesize(com.brahmadeo.supertonic.tts.pocket.PocketText.modelPrompt(chunk),probeVoice.path,1f,
                                            object: com.brahmadeo.supertonic.tts.pocket.NativePocketTts.AudioSink {
                                                override fun onAudio(samples: FloatArray): Boolean {
                                                    val bytes=ByteBuffer.allocate(samples.size*2).order(ByteOrder.LITTLE_ENDIAN)
                                                    samples.forEach { bytes.putShort((it*32767).toInt().coerceIn(-32768,32767).toShort()) }
                                                    audio.write(bytes.array());return true
                                                }
                                            },seed+part+index*100))
                                    }
                                    val pcm=audio.toByteArray()
                                    val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                                    header.put("RIFF".toByteArray()).putInt(pcm.size+36).put("WAVEfmt ".toByteArray()).putInt(16)
                                        .putShort(1.toShort()).putShort(1.toShort()).putInt(24000).putInt(48000).putShort(2.toShort()).putShort(16.toShort())
                                        .put("data".toByteArray()).putInt(pcm.size)
                                    File(cacheDir,"tail-$tail-$index.wav").outputStream().use { it.write(header.array());it.write(pcm) }
                                    Log.i("SpeechCheck","TAIL PROBE voice=$selectedProbeVoice steps=$decodeSteps seed=$seed tail=$tail case=$index audioMs=${pcm.size*1000L/48000}")
                                }
                            }
                        }
                        return@withContext
                    }
                    for ((index, text) in cases.withIndex()) {
                        if (index == 1) {
                            delay(6000)
                            if(intent.getBooleanExtra("cache",false)) {
                                val before=com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.generation
                                com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.clear()
                                check(com.brahmadeo.supertonic.tts.utils.SpeechPreparationCache.generation>before)
                                Log.i("SpeechCheck","Manual cache invalidation before repeated synthesis")
                            }
                        }
                        val language = if(bookProbe) "ru" else when (index) { 3 -> "en"; 4 -> "cs"; else -> "ru" }
                        check(tts!!.setLanguage(Locale(language)) >= TextToSpeech.LANG_AVAILABLE)
                        val id = "speech-check-$index"; val done = CompletableFuture<Unit>()
                        tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(Unit) }
                            override fun onError(utteranceId: String?) {
                                if (utteranceId == id) done.completeExceptionally(IllegalStateException("TTS case $index failed"))
                            }
                        })
                        val output = File(cacheDir, "speech-check-$index.wav")
                        try {
                            val started = android.os.SystemClock.elapsedRealtime()
                            check(tts!!.synthesizeToFile(text, Bundle(), output, id) == TextToSpeech.SUCCESS)
                            done.get(90, TimeUnit.SECONDS)
                            val header = ByteArray(44)
                            output.inputStream().use { check(it.read(header) == 44) }
                            check(String(header, 0, 4, Charsets.US_ASCII) == "RIFF")
                            val wave = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                            val rate = wave.getInt(24); val channels = wave.getShort(22).toInt()
                            check(rate == when(model) { AssetManager.KOKORO_MODEL,AssetManager.POCKET_MODEL -> 24000; AssetManager.SILERO_MODEL,AssetManager.SILERO_CIS_MODEL -> 48000; else -> 44100 })
                            check(channels == 1 && output.length() > 44)
                            Log.i("SpeechCheck", "PASS model=$model case=$index rate=$rate channels=$channels bytes=${output.length()} ms=${android.os.SystemClock.elapsedRealtime()-started}")
                        } finally { if (!intent.getBooleanExtra("retainAudio",false)) output.delete() }
                    }
                    if(intent.getBooleanExtra("contention",false)) {
                        val probe=com.brahmadeo.supertonic.tts.utils.TextNormalizer().normalize("Проверка выдачи готового аудио во время фонового синтеза.","ru")
                        val style=AssetManager.voiceFile(this@SpeechDiagnosticsActivity,prefs.getString("selected_voice","ru_f1.json")!!).path
                        val steps=prefs.getInt("diffusion_steps",5)
                        SupertonicTTS.setCancelled(false)
                        val expected=SupertonicTTS.generateAudio(probe,"ru",style,1.1f,0f,steps,2.5f)
                        check(expected!=null && expected.isNotEmpty())
                        val locked=java.util.concurrent.CountDownLatch(1)
                        val release=java.util.concurrent.CountDownLatch(1)
                        val holder=Thread {
                            synchronized(SupertonicTTS) {
                                locked.countDown()
                                release.await(3,TimeUnit.SECONDS)
                            }
                        }
                        holder.start()
                        try {
                            check(locked.await(3,TimeUnit.SECONDS))
                            var delivered=0
                            val listener=object : SupertonicTTS.ProgressListener {
                                override fun onProgress(sessionId: Long,current: Int,total: Int) {}
                                override fun onAudioChunk(sessionId: Long,data: ByteArray) { delivered+=data.size }
                            }
                            val started=android.os.SystemClock.elapsedRealtime()
                            val cached=SupertonicTTS.generateAudio(probe,"ru",style,1.1f,0f,steps,2.5f,listener)
                            val elapsed=android.os.SystemClock.elapsedRealtime()-started
                            check(cached===expected && delivered==expected.size)
                            check(elapsed<500 && holder.isAlive) { "Cached audio waited for model lock: ${elapsed}ms" }
                            Log.i("SpeechCheck","CACHE WHILE MODEL BUSY PASSED model=$model ms=$elapsed bytes=$delivered")
                        } finally { release.countDown(); holder.join(3000) }
                    }
                    if(intent.getBooleanExtra("queue",false)) {
                        val queued=listOf("Первая контрольная фраза для проверки непрерывного чтения.","Вторая контрольная фраза должна быть заранее подготовлена.","Третья контрольная фраза завершает проверку очереди.")
                        val finished=java.util.concurrent.CountDownLatch(queued.size)
                        val failures=java.util.concurrent.atomic.AtomicInteger()
                        tts!!.setLanguage(Locale("ru"))
                        tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) { Log.i("SpeechCheck","Silent queued start=$id") }
                            override fun onDone(id: String?) { finished.countDown() }
                            override fun onError(id: String?) { failures.incrementAndGet();finished.countDown() }
                        })
                        val quiet=Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,0f) }
                        queued.forEachIndexed { i,text -> check(tts!!.speak(text,TextToSpeech.QUEUE_ADD,quiet,"queue-check-$i")==TextToSpeech.SUCCESS) }
                        check(finished.await(90,TimeUnit.SECONDS) && failures.get()==0)
                        Log.i("SpeechCheck","SILENT QUEUE PASSED model=$model")
                    }
                    if(intent.getBooleanExtra("idleProbe",false)) {
                        check(model == AssetManager.TERA_MODEL)
                        val sample = "Контроль сохранения готового звука после простоя."
                        val style = AssetManager.voiceFile(this@SpeechDiagnosticsActivity,"ru_f1.json").path
                        SupertonicTTS.setCancelled(false)
                        val cached = SupertonicTTS.generateAudio(sample,"ru",style,1.1f,0f,5,2.5f)
                        check(cached != null && cached.isNotEmpty() && SupertonicTTS.teraResident())
                        val started = android.os.SystemClock.elapsedRealtime()
                        Log.i("SpeechCheck","IDLE PROBE START: waiting for real two-minute model unload")
                        while(SupertonicTTS.teraResident() && android.os.SystemClock.elapsedRealtime()-started < 150000) delay(1000)
                        check(!SupertonicTTS.teraResident()) { "Tera stayed resident after idle" }
                        val retrieval = android.os.SystemClock.elapsedRealtime()
                        val hit = SupertonicTTS.generateAudio(sample,"ru",style,1.1f,0f,5,2.5f)
                        check(hit === cached && !SupertonicTTS.teraResident()) { "Idle unload lost PCM or reloaded model for a cache hit" }
                        Log.i("SpeechCheck","IDLE PROBE PASSED waitMs=${android.os.SystemClock.elapsedRealtime()-started} cachedMs=${android.os.SystemClock.elapsedRealtime()-retrieval} bytes=${hit!!.size}")
                    }
                    Log.i("SpeechCheck", "ALL CASES PASSED model=$model")
                } catch (t: Throwable) { Log.e("SpeechCheck", "Integration check failed", t) }
                finally {
                    tts?.stop(); tts?.shutdown()
                    if(verifyStress) prefs.edit().putBoolean("local_russian_stress",oldLocalStress).commit()
                    if(offline || multiVoiceProbe || voicePreviewProbe) {
                        llmPrefs.edit().putBoolean("multi_voice",oldMultiVoice).putString("mode",oldLlmMode).putBoolean("restore_yo",oldRestoreYo).commit()
                        com.brahmadeo.supertonic.tts.llm.LlmPreparation.settingsChanged()
                    }
                    AssetManager.setModelType(this@SpeechDiagnosticsActivity, oldModel)
                    prefs.edit().putString("selected_voice", oldVoice).putString("selected_lang", oldLang).apply()
                    SupertonicTTS.release()
                    Log.i("SpeechCheck", "Original model and voice restored")
                    running.set(false)
                }
            }
            finish()
        }
    }
}
