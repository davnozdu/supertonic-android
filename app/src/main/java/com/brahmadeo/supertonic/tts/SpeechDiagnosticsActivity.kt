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
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!running.compareAndSet(false,true)) {
            Log.w("SpeechCheck","Diagnostic already running; duplicate ignored")
            finish()
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
                val oldModel = AssetManager.getModelType(this@SpeechDiagnosticsActivity)
                val oldVoice = prefs.getString("selected_voice", "F3.json")
                val llmPrefs=getSharedPreferences("llm_settings",MODE_PRIVATE)
                val oldLlmMode=llmPrefs.getString("mode","OFF")
                val oldRestoreYo=llmPrefs.getBoolean("restore_yo",true)
                val offline=intent.getBooleanExtra("offline",false)
                val oldLocalStress=prefs.getBoolean("local_russian_stress",true)
                val verifyStress=intent.getBooleanExtra("stress",false)
                val oldLang = prefs.getString("selected_lang", "en")
                val model = intent.getStringExtra("model") ?: AssetManager.SILERO_MODEL
                var tts: TextToSpeech? = null
                try {
                    if(verifyStress) prefs.edit().putBoolean("local_russian_stress",true).commit()
                    if(offline) {
                        llmPrefs.edit().putString("mode","OFF").commit()
                        com.brahmadeo.supertonic.tts.llm.LlmPreparation.settingsChanged()
                    }
                    require(model in setOf(AssetManager.SILERO_MODEL, AssetManager.SILERO_CIS_MODEL, AssetManager.TERA_MODEL, AssetManager.POCKET_MODEL,"standard","android_optimized_int8","android_optimized_fp16","android_optimized_fp32"))
                    AssetManager.setModelType(this@SpeechDiagnosticsActivity, model)
                    prefs.edit().putString("selected_lang", "ru")
                        .putString("selected_voice", when(model) { AssetManager.POCKET_MODEL -> "alba.json"; AssetManager.SILERO_CIS_MODEL -> "ru_alexandr.json"; AssetManager.SILERO_MODEL -> "kseniya.json"; AssetManager.TERA_MODEL -> "ru_f1.json"; else -> "F3.json" }).apply()
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
                        for(tail in listOf(-2,-1)) {
                            com.brahmadeo.supertonic.tts.pocket.NativePocketTts(root.path,root.path,"fp32",.3f,1,4,180,50,tail).use { engine ->
                                for((index,text) in prepared.withIndex()) {
                                    val audio=java.io.ByteArrayOutputStream()
                                    for(chunk in com.brahmadeo.supertonic.tts.pocket.PocketText.chunks(text)) {
                                        check(engine.synthesize(com.brahmadeo.supertonic.tts.pocket.PocketText.modelPrompt(chunk),File(root,"alba.wav").path,1f,
                                            object: com.brahmadeo.supertonic.tts.pocket.NativePocketTts.AudioSink {
                                                override fun onAudio(samples: FloatArray): Boolean {
                                                    val bytes=ByteBuffer.allocate(samples.size*2).order(ByteOrder.LITTLE_ENDIAN)
                                                    samples.forEach { bytes.putShort((it*32767).toInt().coerceIn(-32768,32767).toShort()) }
                                                    audio.write(bytes.array());return true
                                                }
                                            }))
                                    }
                                    val pcm=audio.toByteArray()
                                    val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                                    header.put("RIFF".toByteArray()).putInt(pcm.size+36).put("WAVEfmt ".toByteArray()).putInt(16)
                                        .putShort(1.toShort()).putShort(1.toShort()).putInt(24000).putInt(48000).putShort(2.toShort()).putShort(16.toShort())
                                        .put("data".toByteArray()).putInt(pcm.size)
                                    File(cacheDir,"tail-$tail-$index.wav").outputStream().use { it.write(header.array());it.write(pcm) }
                                    Log.i("SpeechCheck","TAIL PROBE tail=$tail case=$index audioMs=${pcm.size*1000L/48000}")
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
                            check(rate == when(model) { AssetManager.POCKET_MODEL -> 24000; AssetManager.SILERO_MODEL,AssetManager.SILERO_CIS_MODEL -> 48000; else -> 44100 })
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
                    Log.i("SpeechCheck", "ALL CASES PASSED model=$model")
                } catch (t: Throwable) { Log.e("SpeechCheck", "Integration check failed", t) }
                finally {
                    tts?.stop(); tts?.shutdown()
                    if(verifyStress) prefs.edit().putBoolean("local_russian_stress",oldLocalStress).commit()
                    if(offline) {
                        llmPrefs.edit().putString("mode",oldLlmMode).putBoolean("restore_yo",oldRestoreYo).commit()
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
