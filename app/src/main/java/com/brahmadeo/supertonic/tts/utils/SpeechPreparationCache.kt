package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Invalidates future synthesis without stopping the audio currently playing. */
object SpeechPreparationCache {
    private val revision = AtomicLong()
    val generation: Long get() = revision.get()
    private val cleaner = Executors.newSingleThreadExecutor { Thread(it,"SpeechCacheClear").apply { isDaemon=true } }
    private val keys = setOf("selected_model","selected_voice","selected_voice_2","is_mixing_enabled","mix_alpha","is_advanced_normalization","selected_lang","speed","diffusion_steps",
        "local_russian_stress","reader_early_prepare","reader_pcm_cache_mb","tera_punctuation_pauses","tera_short_word_cap","tera_teacher","kokoro_full_precision",
        "tera_comma_pause_ms","tera_sentence_pause_ms","silero_intonation","silero_fixed_pauses","foreign_tts","foreign_engine","foreign_language")
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key?.startsWith(EngineThreadPolicy.PREFIX) == true) {
            revision.incrementAndGet()
            context?.let { com.brahmadeo.supertonic.tts.llm.ReaderAudioAhead.invalidate(it) } ?: com.brahmadeo.supertonic.tts.llm.ReaderAudioAhead.cancel()
            com.brahmadeo.supertonic.tts.SupertonicTTS.clearAudioCache()
        } else if(key == null || key in keys) {
            clear()
            if (key == "selected_model") cleaner.execute { com.brahmadeo.supertonic.tts.SupertonicTTS.releaseInactive() }
        }
    }
    private var prefs: SharedPreferences? = null
    @Volatile private var context: Context? = null
    @Synchronized fun initialize(context: Context) {
        if(prefs != null) return
        this.context = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences("SupertonicPrefs",0).also { it.registerOnSharedPreferenceChangeListener(listener) }
    }
    fun clear() {
        revision.incrementAndGet()
        // LLM first: requeued look-ahead must not be cancelled by the LLM invalidation.
        com.brahmadeo.supertonic.tts.llm.LlmPreparation.settingsChanged()
        context?.let { com.brahmadeo.supertonic.tts.llm.ReaderAudioAhead.invalidate(it) } ?: com.brahmadeo.supertonic.tts.llm.ReaderAudioAhead.cancel()
        cleaner.execute {
            com.brahmadeo.supertonic.tts.local.LocalRussianStress.clearCache()
            com.brahmadeo.supertonic.tts.SupertonicTTS.clearAudioCache()
            com.brahmadeo.supertonic.tts.foreign.ForeignTts.clearAudioCache()
        }
        android.util.Log.i("SpeechCache","Preparation caches invalidated generation=$generation; current playback retained")
    }
}
