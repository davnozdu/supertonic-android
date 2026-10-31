package com.brahmadeo.supertonic.tts.service

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import android.content.Context
import android.os.Build
import com.brahmadeo.supertonic.tts.SupertonicTTS
import com.brahmadeo.supertonic.tts.utils.AssetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

class SupertonicTextToSpeechService : TextToSpeechService() {
    private val queueObservers = java.util.concurrent.CopyOnWriteArrayList<com.brahmadeo.supertonic.tts.llm.TtsQueueObserver>()
    override fun onBind(intent: android.content.Intent): android.os.IBinder? {
        val binder = super.onBind(intent) ?: return null
        return com.brahmadeo.supertonic.tts.llm.TtsQueueObserver(this, binder).also { queueObservers.add(it) }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var initJob: Job? = null
    @Volatile private var activeVoicePreview = false
    /** Stopping a voice preview must not cancel other synthesis (the app's own player may be reading a book):
     * only the preview request stops, without the process-wide cancel flag. */
    @Volatile private var previewStopped = false

    private val attributionContext: Context by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createAttributionContext("supertonic_playback")
        } else {
            this
        }
    }

    companion object {
        const val VOLUME_BOOST_FACTOR = 2.5f

        // ISO-639-2/3 language codes Android may pass us → our internal 2-letter Supertonic codes.
        private val LANG_PREFIX_MAP: Map<String, String> = mapOf(
            "en" to "en", "eng" to "en",
            "ko" to "ko", "kor" to "ko",
            "ja" to "ja", "jpn" to "ja",
            "ar" to "ar", "ara" to "ar",
            "bg" to "bg", "bul" to "bg",
            "cs" to "cs", "ces" to "cs", "cze" to "cs",
            "da" to "da", "dan" to "da",
            "de" to "de", "deu" to "de", "ger" to "de",
            "el" to "el", "ell" to "el", "gre" to "el",
            "es" to "es", "spa" to "es",
            "et" to "et", "est" to "et",
            "fi" to "fi", "fin" to "fi",
            "fr" to "fr", "fra" to "fr", "fre" to "fr",
            "hi" to "hi", "hin" to "hi",
            "hr" to "hr", "hrv" to "hr",
            "hu" to "hu", "hun" to "hu",
            "id" to "id", "ind" to "id",
            "it" to "it", "ita" to "it",
            "lt" to "lt", "lit" to "lt",
            "lv" to "lv", "lav" to "lv",
            "nl" to "nl", "nld" to "nl", "dut" to "nl",
            "pl" to "pl", "pol" to "pl",
            "pt" to "pt", "por" to "pt",
            "ro" to "ro", "ron" to "ro", "rum" to "ro",
            "ru" to "ru", "rus" to "ru",
            "sk" to "sk", "slk" to "sk", "slo" to "sk",
            "sl" to "sl", "slv" to "sl",
            "sv" to "sv", "swe" to "sv",
            "tr" to "tr", "tur" to "tr",
            "uk" to "uk", "ukr" to "uk",
            "vi" to "vi", "vie" to "vi"
        )

        // Reverse map: our 2-letter codes → preferred ISO-639-3 form to advertise to Android (with country).
        private val ANDROID_LOCALE_TRIPLES: List<Triple<String, String, String>> = listOf(
            Triple("en", "eng", "USA"),
            Triple("ko", "kor", "KOR"),
            Triple("ja", "jpn", "JPN"),
            Triple("ar", "ara", "ARA"),
            Triple("bg", "bul", "BGR"),
            Triple("cs", "ces", "CZE"),
            Triple("da", "dan", "DNK"),
            Triple("de", "deu", "DEU"),
            Triple("el", "ell", "GRC"),
            Triple("es", "spa", "ESP"),
            Triple("et", "est", "EST"),
            Triple("fi", "fin", "FIN"),
            Triple("fr", "fra", "FRA"),
            Triple("hi", "hin", "IND"),
            Triple("hr", "hrv", "HRV"),
            Triple("hu", "hun", "HUN"),
            Triple("id", "ind", "IDN"),
            Triple("it", "ita", "ITA"),
            Triple("lt", "lit", "LTU"),
            Triple("lv", "lav", "LVA"),
            Triple("nl", "nld", "NLD"),
            Triple("pl", "pol", "POL"),
            Triple("pt", "por", "PRT"),
            Triple("ro", "ron", "ROU"),
            Triple("ru", "rus", "RUS"),
            Triple("sk", "slk", "SVK"),
            Triple("sl", "slv", "SVN"),
            Triple("sv", "swe", "SWE"),
            Triple("tr", "tur", "TUR"),
            Triple("uk", "ukr", "UKR"),
            Triple("vi", "vie", "VNM")
        )
    }

    override fun onCreate() {
        super.onCreate()
        SupertonicTTS.setApplicationContext(this)
        Log.i("SupertonicTTS", "Service created")
        CallInterruption.register(this, this) { queueObservers.forEach { it.stopForCall() } }
        SleepTimer.initialize(this)
        ReadingControls.register(this, this, { queueObservers.forEach { it.stopForCall() } }, { queueObservers.forEach { it.stopForCall() } })
        com.brahmadeo.supertonic.tts.utils.LexiconManager.load(this)
        com.brahmadeo.supertonic.tts.books.smart.SmartFolder.scanIfDue(this)
        com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.load(this)
        com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.load(this)
        com.brahmadeo.supertonic.tts.utils.PlaybackPrefs.load(this)

        initJob = serviceScope.launch(Dispatchers.IO) {
            val modelPath = File(filesDir, "${AssetManager.MODEL_VERSION}/onnx").absolutePath
            val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
            if (!AssetManager.isRussianModel(this@SupertonicTextToSpeechService) &&
                AssetManager.getModelType(this@SupertonicTextToSpeechService) != "android_optimized_int8") {
                SupertonicTTS.initialize(modelPath, libPath,
                    xnnThreads = SupertonicTTS.recommendedXnnThreads(this@SupertonicTextToSpeechService))
            }
            // Prewarm (see PlaybackService for rationale). Idempotent — if
            // PlaybackService was up first and warmed, this is a no-op.
            val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
            val voiceFile = prefs.getString("selected_voice", "F3.json") ?: "F3.json"
            val stylePath = AssetManager.voiceFile(this@SupertonicTextToSpeechService, voiceFile).absolutePath
            SupertonicTTS.prewarm(stylePath)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        com.brahmadeo.supertonic.tts.music.BackgroundMusic.stopTts()
        CallInterruption.unregister(this)
        ReadingControls.unregister(this)
        queueObservers.forEach { it.close() }; queueObservers.clear()
        serviceScope.cancel()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val language = lang?.lowercase(Locale.ROOT) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        // A Russian model reads the languages connected in «Мультиязычная озвучка» through their engines.
        val supported = if (AssetManager.isRussianModel(this)) language.startsWith("ru") || language.startsWith("rus") ||
            normalizeLanguage(language) in com.brahmadeo.supertonic.tts.foreign.ForeignTts.connected(this)
            else LANG_PREFIX_MAP.keys.any { language.startsWith(it) }
        if (!supported) return TextToSpeech.LANG_NOT_SUPPORTED

        return if (AssetManager.isReady(this)) {
            if (!country.isNullOrEmpty()) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
        } else {
            TextToSpeech.LANG_MISSING_DATA
        }
    }

    override fun onGetLanguage(): Array<String> {
        val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
        val selectedLang = prefs.getString("selected_lang", "en") ?: "en"
        val triple = ANDROID_LOCALE_TRIPLES.find { it.first == selectedLang }
            ?: ANDROID_LOCALE_TRIPLES.first() // fall back to English
        return arrayOf(triple.second, triple.third, "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onLoadVoice(voiceName: String?): Int {
        if (voiceName == null) return TextToSpeech.ERROR
        if (com.brahmadeo.supertonic.tts.foreign.MultilingualVoice.matches(voiceName))
            return if (multilingualReady()) TextToSpeech.SUCCESS else TextToSpeech.ERROR
        if (!voiceName.contains("-supertonic-")) return TextToSpeech.ERROR
        val styleName = voiceName.substringAfter("-supertonic-")
        if (AssetManager.isRussianModel(this) && styleName !in AssetManager.russianVoices(this)) return TextToSpeech.ERROR
        val file = AssetManager.voiceFile(this, "$styleName.json")
        return if (file.exists()) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
        val selected = prefs.getString("selected_voice", "F3.json") ?: "F3.json"
        val voiceName = if (selected.endsWith(".json")) selected.substringBeforeLast(".") else selected
        val requested = normalizeLanguage(lang)
        val prefix = if (AssetManager.isRussianModel(this) && requested !in com.brahmadeo.supertonic.tts.foreign.ForeignTts.connected(this)) "ru" else requested
        return "$prefix-supertonic-$voiceName"
    }

    /** Voices are ours by name; the default check parses the name as a locale tag, which ours are not. */
    override fun onIsValidVoiceName(voiceName: String?): Int = onLoadVoice(voiceName)

    /** «MyTTS Мультиголос» / «MyTTS Один голос»: any ready Russian model; other languages only when connected. */
    private fun multilingualReady() = AssetManager.isRussianModel(this) && AssetManager.isReady(this)

    override fun onGetVoices(): List<Voice> {
        val voicesList = mutableListOf<Voice>()
        val russian = AssetManager.isRussianModel(this)
        val voiceNames = if (russian) AssetManager.russianVoices(this)
            else listOf("M1", "M2", "M3", "M4", "M5", "F1", "F2", "F3", "F4", "F5")
        if (!AssetManager.isReady(this)) return voicesList
        // One PackageManager query, not one per advertised locale.
        val foreign = if (russian) com.brahmadeo.supertonic.tts.foreign.ForeignTts.connected(this) else emptyList()

        // «MyTTS Мультиголос» and «MyTTS Один голос», first among the Russian voices (Readest takes the first of the
        // book's language), also without other languages: readers that set a voice choose one or several voices here.
        if (russian) listOf(com.brahmadeo.supertonic.tts.foreign.MultilingualVoice.NAME, com.brahmadeo.supertonic.tts.foreign.MultilingualVoice.SINGLE).forEach {
            voicesList.add(Voice(it, Locale.forLanguageTag("ru"), Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, false, setOf()))
        }
        ANDROID_LOCALE_TRIPLES.forEach { (twoLetter, _, _) ->
            if (russian && twoLetter != "ru" && twoLetter !in foreign) return@forEach
            val locale = Locale.forLanguageTag(twoLetter)
            voiceNames.forEach { name ->
                voicesList.add(
                    Voice(
                        "$twoLetter-supertonic-$name",
                        locale,
                        Voice.QUALITY_VERY_HIGH,
                        Voice.LATENCY_NORMAL,
                        false,
                        setOf()
                    )
                )
            }
        }
        return voicesList
    }

    override fun onStop() {
        if (activeVoicePreview) { previewStopped = true; return }
        com.brahmadeo.supertonic.tts.llm.ReaderAudioAhead.cancel()
        SupertonicTTS.setCancelled(true)
    }

    private fun normalizeLanguage(lang: String?): String {
        if (lang == null) return "en"
        val l = lang.lowercase(Locale.ROOT)
        return LANG_PREFIX_MAP.entries.firstOrNull { l.startsWith(it.key) }?.value ?: "en"
    }

    /**
     * Override the requested language when the actual text content tells us
     * otherwise. Apps like Moon+ Reader sometimes don't set the language
     * field, so we'd get whatever the system locale is — and a Russian
     * audiobook would land in our English path, missing Cyrillic stress
     * marks and number-to-words spellout.
     *
     * Counts Cyrillic vs Latin letters and overrides only when Cyrillic is
     * the clear majority. Threshold tuned to avoid flipping on isolated
     * proper nouns inside an English text ("Pushkin", "Tolstoy").
     */
    private fun detectLanguage(text: String, requested: String): String {
        var cyrillic = 0
        var latin = 0
        for (ch in text) {
            when {
                ch in 'Ѐ'..'ӿ' -> cyrillic++
                ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
            }
        }
        return if (cyrillic > latin && cyrillic >= 4) "ru" else requested
    }

    private val textNormalizer = com.brahmadeo.supertonic.tts.utils.TextNormalizer()

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        if (CallInterruption.active()) { callback.error(TextToSpeech.ERROR_SERVICE); return }
        if (SleepTimer.blocked) { callback.error(TextToSpeech.ERROR_SERVICE); return }
        // A failed external-engine connection must never delegate back into us.
        if (request.params.getBoolean("supertonic_foreign_proxy", false)) {
            callback.error(); callback.done(); return
        }
        val voicePreview = com.brahmadeo.supertonic.tts.llm.VoicePreview.requested(this, request.params, request.callerUid)
        activeVoicePreview = voicePreview
        previewStopped = false
        fun stopped() = SupertonicTTS.isCancelled() || (voicePreview && previewStopped)
        try {
        SupertonicTTS.setCancelled(false)
        runBlocking {
            withTimeoutOrNull(5000) {
                initJob?.join()
            }
        }
        val incomingText = request.charSequenceText?.toString() ?: return
        // Another app's message over a reading (ForeignSpeech): read at once by the dictionaries, the reader paused.
        val foreignMessage = !voicePreview && ForeignSpeech.foreign(this, request.callerUid)
        if (!voicePreview) ForeignSpeech.note(request.callerUid)
        if (!voicePreview && !foreignMessage) ReaderApp.note(this, request.callerUid)
        if (foreignMessage) ForeignSpeech.begin(this)
        val foreign = foreignMessage || (!voicePreview && ForeignSpeech.isAside(incomingText))
        // «Умная книга»: the place being read now (readers queue ahead, synthesis follows the voice).
        if (!voicePreview && !foreign) com.brahmadeo.supertonic.tts.books.smart.SmartMatcher.heard(this, request.callerUid, incomingText)
        // KOReader hands over one sentence at a time; its plugin sent the pages ahead and their paragraphs were prepared
        // by the LLM (KoreaderAhead): the sentence is cut out of its prepared paragraph. Not prepared yet: dictionaries.
        val aheadText = if (voicePreview || foreign) null else com.brahmadeo.supertonic.tts.llm.ReaderAudioAhead.takePrepared(incomingText)
            ?: com.brahmadeo.supertonic.tts.llm.KoreaderAhead.sentence(this, incomingText)
        val preparation = if (aheadText == null && !voicePreview && !foreign) com.brahmadeo.supertonic.tts.llm.LlmPreparation.prepareResult(this, incomingText) else null
        val llmProcessed = aheadText?.llmProcessed ?: (preparation?.fallback == false)
        // «Умная книга»: the retelling is stressed by Silero Stress alone (stress and ё), as the user chose; no LLM,
        // roles or music.
        val recap = voicePreview && request.params?.getBoolean(packageName + ".smart_recap", false) == true
        val preserveMarks = llmProcessed || (voicePreview && !recap)
        val rawText = aheadText?.text ?: preparation?.text ?: incomingText
        val traceId=com.brahmadeo.supertonic.tts.llm.SpeechTextTrace.fingerprint(incomingText)
        com.brahmadeo.supertonic.tts.utils.DiagLog.i("LlmPreparation", "TTS trace source=$traceId prepared=${com.brahmadeo.supertonic.tts.llm.SpeechTextTrace.fingerprint(rawText)} llm=$llmProcessed ahead=${aheadText!=null}")
        com.brahmadeo.supertonic.tts.utils.DiagLog.i("LlmPreparation", "Speech path=${if(voicePreview) "voice preview; LLM bypassed" else if(llmProcessed) "LLM; internal stress bypassed" else "offline fallback"} chars=${rawText.length}")
        if(aheadText!=null) com.brahmadeo.supertonic.tts.utils.DiagLog.i("ReaderAhead","Using prepared text without repeated LLM wait chars=${incomingText.length}")
        if (stopped()) { callback.error(); callback.done(); return }
        val requestStarted = android.os.SystemClock.elapsedRealtime()
        Log.i("SupertonicTTS", "TTS request started: chars=${rawText.length}, model=${AssetManager.getModelType(this)}")
        val unusualSpaces=com.brahmadeo.supertonic.tts.utils.BookTextSpacing.unusualSpaceCount(rawText)
        if(unusualSpaces>0) Log.i("SupertonicTTS","Book typography separators=$unusualSpaces; word boundaries preserved")
        val effectiveSpeed = (request.speechRate / 100.0f).coerceIn(0.5f, 2.5f)
        // KOReader's plugin reads ahead through MyTTS: its audio must be made with this voice and rate to be found.
        if (!voicePreview && request.callerUid == com.brahmadeo.supertonic.tts.llm.KoreaderAhead.readerUid)
            com.brahmadeo.supertonic.tts.llm.KoreaderAhead.observe(request.voiceName, effectiveSpeed)
        callback.start(SupertonicTTS.getAudioSampleRate(), android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        val requestedVoice = request.voiceName
        val requestedLang = detectLanguage(rawText, normalizeLanguage(request.language))
        if (AssetManager.isRussianModel(this) && requestedLang != "ru" &&
            !com.brahmadeo.supertonic.tts.foreign.ForeignTts.available(this)) {
            callback.error()
            return
        }
        val prefs = attributionContext.getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)

        // «Мультиязычный» reads like a reader without a voice: the voice chosen in MyTTS, the book's voices.
        val voiceFile = if (requestedVoice != null && requestedVoice.contains("-supertonic-") &&
            !com.brahmadeo.supertonic.tts.foreign.MultilingualVoice.matches(requestedVoice)) {
            val fileName = requestedVoice.substringAfter("-supertonic-")
            // Sanitize fileName to prevent path traversal
            File(fileName).name + ".json"
        } else {
            prefs.getString("selected_voice", "F3.json") ?: "F3.json"
        }

        val voiceStyleDir = File(filesDir, "${AssetManager.MODEL_VERSION}/voice_styles")
        var stylePath = AssetManager.voiceFile(this, voiceFile).absolutePath
        val previewVoice = if (voicePreview) request.params?.getString(packageName + ".book_voice") else null
        if (previewVoice != null) {
            val file = com.brahmadeo.supertonic.tts.books.BookVoiceCatalog.file(this, previewVoice)
            if (file == null) { callback.error(); return }
            stylePath = file.absolutePath
        }
        if (voicePreview) Log.i("VoicePreview", "Synthesis voice=${voiceFile.removeSuffix(".json")} model=${AssetManager.getModelType(this)}")

        // Ensure stylePath is within the intended directory
        if (previewVoice == null && !AssetManager.isRussianModel(this) && !File(stylePath).canonicalPath.startsWith(voiceStyleDir.canonicalPath)) {
            stylePath = AssetManager.voiceFile(this, "F3.json").absolutePath
        }

        val isMixing = prefs.getBoolean("is_mixing_enabled", false)
        if (isMixing && !AssetManager.isRussianModel(this)) {
            val voice2 = prefs.getString("selected_voice_2", "M2.json") ?: "M2.json"
            val stylePath2 = File(voiceStyleDir, voice2).absolutePath
            val alpha = prefs.getFloat("mix_alpha", 0.5f)
            if (File(stylePath).exists() && File(stylePath2).exists()) {
                stylePath = "$stylePath;$stylePath2;$alpha"
            }
        }

        val steps = prefs.getInt("diffusion_steps", 5)

        if (!AssetManager.isRussianModel(this) && AssetManager.getModelType(this) != "android_optimized_int8" && SupertonicTTS.getSoC() == -1) {
            val modelPath = File(filesDir, "${AssetManager.MODEL_VERSION}/onnx").absolutePath
            val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
            SupertonicTTS.initialize(modelPath, libPath,
                xnnThreads = SupertonicTTS.recommendedXnnThreads(this@SupertonicTextToSpeechService))
        }

        // Synthesize on a worker and pass PCM through a bounded channel.
        // The synthesis thread calls audioAvailable as each chunk arrives;
        // Android forbids calling that callback from the worker thread.
        // Keep several sentences of already generated PCM in RAM while the
        // framework drains earlier audio. The next Android TTS request itself
        // is also observed when the reader queues it through the Binder proxy.
        val ttsChannel = kotlinx.coroutines.channels.Channel<Feed>(capacity = 500)
        val streamingListener = object : SupertonicTTS.ProgressListener {
            override fun onProgress(sessionId: Long, current: Int, total: Int) {}
            override fun onAudioChunk(sessionId: Long, data: ByteArray) {
                if (stopped()) return
                // Block on send instead of busy-waiting. See PlaybackService
                // for the same pattern + rationale (no CPU burn vs the old
                // 50 Hz trySend poll loop).
                try {
                    runBlocking { ttsChannel.send(Feed.Audio(data)) }
                } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
                    // Producer closed the channel — fine.
                } catch (_: InterruptedException) {
                    // Caller interrupted us — return cleanly.
                }
            }
        }

        var success = true
        var anyAudio = false
        var failedSentences = 0
        var firstAudioLogged = false
        var deliveredBytes = 0L
        var firstAudioAt = 0L
        runBlocking {
            val producer = launch(Dispatchers.IO) {
                try {
                    // Готовая книга знает голоса точно: её разметка главнее того, что успело подготовиться наперёд
                    // (в упреждении мог остаться план от прошлой версии книги или от прежней разметки).
                    val plan = if (preparation?.roleProvider == "book") preparation.voicePlan
                               else aheadText?.voicePlan ?: preparation?.voicePlan.orEmpty()
                    com.brahmadeo.supertonic.tts.utils.DiagLog.i("MultiVoice",
                        "Plan source=${if (preparation?.roleProvider == "book") "book" else if (aheadText?.voicePlan?.isNotEmpty() == true) "ahead" else "prep"} prep=${preparation?.roleProvider} roles=${plan.joinToString { it.role.name }} chars=${rawText.length}")
                    val parts = if (voicePreview || com.brahmadeo.supertonic.tts.foreign.MultilingualVoice.single(requestedVoice)) listOf(com.brahmadeo.supertonic.tts.llm.MultiVoiceSettings.Route(rawText, stylePath, false)) else com.brahmadeo.supertonic.tts.llm.MultiVoiceSettings.routes(this@SupertonicTextToSpeechService,
                        rawText, plan, stylePath)
                    // Подсветка в читалке: диапазоны фраз в тексте, который прислала читалка.
                    val aligner = if (voicePreview || foreign) null else Aligner(incomingText)
                    val sentences = parts.flatMap { route -> textNormalizer.splitIntoSentences(
                        route.text, requestedLang, preservePunctuation = AssetManager.isRussianModel(this@SupertonicTextToSpeechService)
                    ).map { Triple(it, route.style, route.dialogue) } }
                    for ((sentence, sentenceStyle, line) in sentences) {
                        if (stopped()) { success = false; break }
                        // "* * *", a lone quote or dash: nothing to voice. Never let it end the utterance.
                        if (sentence.none { it.isLetterOrDigit() }) continue
                        val isAdvancedEnabled = prefs.getBoolean("is_advanced_normalization", false)
                        aligner?.locate(sentence)?.let { (start, end) ->
                            runCatching { runBlocking { ttsChannel.send(Feed.Mark(start, end)) } }
                        }
                        val normalizedText = textNormalizer.normalize(sentence, requestedLang, isAdvancedEnabled, skipStress=preserveMarks, sileroOnly=recap)
                        com.brahmadeo.supertonic.tts.utils.DiagLog.i("LlmPreparation", "Synth trace source=$traceId input=${com.brahmadeo.supertonic.tts.llm.SpeechTextTrace.fingerprint(normalizedText)} skipDictionary=$preserveMarks model=${AssetManager.getModelType(this@SupertonicTextToSpeechService)}")
                        val result = SupertonicTTS.asDialogue(line) { SupertonicTTS.generateAudio(
                            normalizedText, requestedLang, sentenceStyle, effectiveSpeed, 0.0f,
                            steps, VOLUME_BOOST_FACTOR, streamingListener, skipDictionary=preserveMarks
                        ) }
                        if (stopped()) { success = false; break }
                        // One failed sentence must not drop the rest of the paragraph: the reader
                        // would treat the error as done and jump ahead (swallowed ending + jump).
                        if (result == null) {
                            failedSentences++
                            Log.w("SupertonicTTS", "Sentence synthesis failed; continuing source=$traceId input=${com.brahmadeo.supertonic.tts.llm.SpeechTextTrace.fingerprint(normalizedText)} chars=${normalizedText.length}")
                        } else anyAudio = true
                    }
                } catch (t: Throwable) {
                    Log.e("SupertonicTTS", "System TTS synthesis failed", t)
                    success = false
                } finally {
                    ttsChannel.close()
                }
            }
            // Android requires audioAvailable on this synthesis thread.
            for (item in ttsChannel) {
                if (stopped()) { success = false; ttsChannel.close(); break }
                if (item is Feed.Mark) {
                    // Точный диапазон текста для подсветки: кадр, с которого он звучит.
                    if (!voicePreview && android.os.Build.VERSION.SDK_INT >= 26) runCatching {
                        callback.rangeStart(item.start, item.end, (deliveredBytes / 2).toInt())
                    }
                    continue
                }
                val data = (item as Feed.Audio).data
                deliveredBytes += data.size
                if (!firstAudioLogged) {
                    firstAudioAt = android.os.SystemClock.elapsedRealtime()
                    firstAudioLogged = true
                    com.brahmadeo.supertonic.tts.utils.DiagLog.i("SupertonicTTS", "TTS first audio after ${android.os.SystemClock.elapsedRealtime() - requestStarted}ms")
                }
                var offset = 0
                while (offset < data.size) {
                    val length = callback.maxBufferSize.coerceIn(1, 4096).coerceAtMost(data.size - offset)
                    if (callback.audioAvailable(data, offset, length) != TextToSpeech.SUCCESS) {
                        success = false
                        if (!voicePreview) SupertonicTTS.setCancelled(true)
                        ttsChannel.close()
                        break
                    }
                    offset += length
                }
            }
            producer.join()
        }
        if (success && !anyAudio && failedSentences > 0) success = false
        // Nothing speakable («).» left of a bracket, a lone dash): a reader that writes the request to a file got an
        // empty WAV it could not play, retried it and reported the engine as dead. A short silence is a valid answer.
        if (success && !anyAudio && !stopped()) {
            val silence = ByteArray(SupertonicTTS.getAudioSampleRate() / 10 * 2)
            if (callback.audioAvailable(silence, 0, minOf(silence.size, callback.maxBufferSize.coerceAtLeast(2))) != TextToSpeech.SUCCESS) success = false
            Log.i("SupertonicTTS", "Nothing to say in chars=${rawText.length}; answered with 100 ms of silence")
        }
        Log.i("SupertonicTTS", "TTS request finished after ${android.os.SystemClock.elapsedRealtime() - requestStarted}ms, success=$success failedSentences=$failedSentences")
        if (success) callback.done() else callback.error()
        if (foreignMessage) ForeignSpeech.end(deliveredBytes * 1000L / (SupertonicTTS.getAudioSampleRate() * 2L).coerceAtLeast(1L),
            if (firstAudioAt > 0) firstAudioAt else android.os.SystemClock.elapsedRealtime())
        } finally { activeVoicePreview = false }
    }

    /** Что идёт в канал синтеза: звук или отметка «эта фраза текста звучит с этого кадра». */
    private sealed class Feed {
        class Audio(val data: ByteArray) : Feed()
        class Mark(val start: Int, val end: Int) : Feed()
    }

    /** Сопоставляет фразы синтеза исходному тексту читалки (LLM переписывает ударения и ё, буквы те же):
     * подсветка в Moon+/Readest идёт по этим диапазонам, иначе внутри длинного абзаца она стоит на месте. */
    private class Aligner(private val text: String) {
        private val norm = StringBuilder()
        private val map = ArrayList<Int>()
        private var cursor = 0
        init {
            for (i in text.indices) {
                val l = text[i].lowercaseChar()
                when {
                    l == 'ё' -> { norm.append('е'); map.add(i) }
                    l.isLetterOrDigit() -> { norm.append(l); map.add(i) }
                }
            }
        }
        private fun clean(sentence: String) = buildString {
            for (c in sentence) { val l = c.lowercaseChar(); if (l == 'ё') append('е') else if (l.isLetterOrDigit()) append(l) }
        }
        /** Диапазон [sentence] в исходном тексте начиная с прошлой фразы; null — не нашли (тогда метку не шлём). */
        fun locate(sentence: String): Pair<Int, Int>? {
            val needle = clean(sentence).take(24)
            if (needle.isEmpty()) return null
            val at = norm.indexOf(needle, cursor)
            if (at < 0) return null
            cursor = at + needle.length
            val start = map[at]
            val end = minOf(text.length, start + sentence.length)
            return start to end
        }
    }
}
