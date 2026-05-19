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

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var initJob: Job? = null

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
        Log.i("SupertonicTTS", "Service created")
        com.brahmadeo.supertonic.tts.utils.LexiconManager.load(this)
        com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.load(this)
        com.brahmadeo.supertonic.tts.utils.PunctuationPrefs.load(this)
        com.brahmadeo.supertonic.tts.utils.PlaybackPrefs.load(this)

        initJob = serviceScope.launch(Dispatchers.IO) {
            val modelPath = File(filesDir, "${AssetManager.MODEL_VERSION}/onnx").absolutePath
            val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
            SupertonicTTS.initialize(modelPath, libPath)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val language = lang?.lowercase(Locale.ROOT) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        val supported = LANG_PREFIX_MAP.keys.any { language.startsWith(it) }
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
        if (!voiceName.contains("-supertonic-")) return TextToSpeech.ERROR
        val styleName = voiceName.substringAfter("-supertonic-")
        val file = File(filesDir, "${AssetManager.MODEL_VERSION}/voice_styles/$styleName.json")
        return if (file.exists()) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
        val selected = prefs.getString("selected_voice", "F3.json") ?: "F3.json"
        val voiceName = if (selected.endsWith(".json")) selected.substringBeforeLast(".") else selected
        val prefix = normalizeLanguage(lang)
        return "$prefix-supertonic-$voiceName"
    }

    override fun onGetVoices(): List<Voice> {
        val voicesList = mutableListOf<Voice>()
        val voiceNames = listOf("M1", "M2", "M3", "M4", "M5", "F1", "F2", "F3", "F4", "F5")
        if (!AssetManager.isReady(this)) return voicesList

        ANDROID_LOCALE_TRIPLES.forEach { (twoLetter, _, _) ->
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
        SupertonicTTS.setCancelled(false)
        runBlocking {
            withTimeoutOrNull(5000) {
                initJob?.join()
            }
        }
        val rawText = request.charSequenceText?.toString() ?: return
        val effectiveSpeed = (request.speechRate / 100.0f).coerceIn(0.5f, 2.5f)
        callback.start(SupertonicTTS.getAudioSampleRate(), android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

        val requestedVoice = request.voiceName
        val requestedLang = detectLanguage(rawText, normalizeLanguage(request.language))
        val prefs = attributionContext.getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)

        val voiceFile = if (requestedVoice != null && requestedVoice.contains("-supertonic-")) {
            val fileName = requestedVoice.substringAfter("-supertonic-")
            // Sanitize fileName to prevent path traversal
            File(fileName).name + ".json"
        } else {
            prefs.getString("selected_voice", "F3.json") ?: "F3.json"
        }

        val voiceStyleDir = File(filesDir, "${AssetManager.MODEL_VERSION}/voice_styles")
        var stylePath = File(voiceStyleDir, voiceFile).absolutePath

        // Ensure stylePath is within the intended directory
        if (!File(stylePath).canonicalPath.startsWith(voiceStyleDir.canonicalPath)) {
            stylePath = File(voiceStyleDir, "F3.json").absolutePath
        }

        val isMixing = prefs.getBoolean("is_mixing_enabled", false)
        if (isMixing) {
            val voice2 = prefs.getString("selected_voice_2", "M2.json") ?: "M2.json"
            val stylePath2 = File(voiceStyleDir, voice2).absolutePath
            val alpha = prefs.getFloat("mix_alpha", 0.5f)
            if (File(stylePath).exists() && File(stylePath2).exists()) {
                stylePath = "$stylePath;$stylePath2;$alpha"
            }
        }

        val steps = prefs.getInt("diffusion_steps", 5)

        if (SupertonicTTS.getSoC() == -1) {
            val modelPath = File(filesDir, "${AssetManager.MODEL_VERSION}/onnx").absolutePath
            val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
            SupertonicTTS.initialize(modelPath, libPath)
        }

        // Streaming + queue pipeline, mirroring PlaybackService.
        // Producer (Rust callback) -> Channel<ByteArray> -> Consumer (audioAvailable).
        //
        // Without this, onSynthesizeText used to wait for an entire sentence
        // of audio (3-5 s) before handing anything over to Android's TTS
        // system. With clients like Moon+ Reader that means a multi-second
        // gap at the start of every block. Now bytes go to audioAvailable
        // chunk-by-chunk as soon as the vocoder produces them, and the
        // 50-chunk buffer lets the producer race ahead while Android plays.
        val ttsChannel = kotlinx.coroutines.channels.Channel<ByteArray>(capacity = 50)
        val streamingListener = object : SupertonicTTS.ProgressListener {
            override fun onProgress(sessionId: Long, current: Int, total: Int) {}
            override fun onAudioChunk(sessionId: Long, data: ByteArray) {
                if (SupertonicTTS.isCancelled()) return
                // Block on send instead of busy-waiting. See PlaybackService
                // for the same pattern + rationale (no CPU burn vs the old
                // 50 Hz trySend poll loop).
                try {
                    runBlocking { ttsChannel.send(data) }
                } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
                    // Producer closed the channel — fine.
                } catch (_: InterruptedException) {
                    // Caller interrupted us — return cleanly.
                }
            }
        }

        val consumerJob = serviceScope.launch(Dispatchers.IO) {
            for (data in ttsChannel) {
                if (SupertonicTTS.isCancelled()) break
                var offset = 0
                while (offset < data.size) {
                    val length = 4096.coerceAtMost(data.size - offset)
                    callback.audioAvailable(data, offset, length)
                    offset += length
                }
            }
        }

        var success = true
        try {
            val sentences = textNormalizer.splitIntoSentences(rawText, requestedLang)
            for (sentence in sentences) {
                if (SupertonicTTS.isCancelled()) { success = false; break }

                val isAdvancedEnabled = prefs.getBoolean("is_advanced_normalization", false)
                val normalizedText = textNormalizer.normalize(sentence, requestedLang, isAdvancedEnabled)

                SupertonicTTS.generateAudio(
                    normalizedText, requestedLang, stylePath, effectiveSpeed, 0.0f,
                    steps, VOLUME_BOOST_FACTOR, streamingListener
                )

                if (SupertonicTTS.isCancelled()) { success = false; break }
            }
        } finally {
            ttsChannel.close()
            runBlocking { consumerJob.join() }
        }
        if (success) callback.done() else callback.error()
    }
}
