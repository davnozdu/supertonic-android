package com.brahmadeo.supertonic.tts.foreign

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioFormat
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.brahmadeo.supertonic.tts.SupertonicTTS
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Synthesis only: the external engine never plays independently of our buffer. */
object ForeignTts {
    data class Engine(val id: String, val title: String, val system: Boolean)
    fun engines(ctx: Context): List<Engine> = ctx.packageManager.queryIntentServices(
        Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).mapNotNull { result ->
        val info = result.serviceInfo
        if (info.packageName == ctx.packageName || info.packageName == "com.brahmadeo.supertonic.tts") null
        else Engine(info.packageName, result.loadLabel(ctx.packageManager).toString(), info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
    }.distinctBy { it.id }
    fun enabled(ctx: Context) = ctx.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getBoolean("foreign_tts", true)
    /** Languages connected in «Мультиязычная озвучка» with their engines. */
    fun languages(ctx: Context): List<ForeignLanguages.Entry> {
        val prefs = ctx.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
        return ForeignLanguages.parse(prefs.getString(ForeignLanguages.KEY, null),
            prefs.getString("foreign_engine", "") ?: "", prefs.getString("foreign_language", "auto") ?: "auto")
    }
    /** Connected languages whose engine is installed; empty when the feature is off. */
    fun connected(ctx: Context): List<String> {
        if (!enabled(ctx)) return emptyList()
        val installed = engines(ctx)
        return languages(ctx).filter { resolve(ctx, it.engine, installed) != null }.map { it.language }
    }
    /** Language of a fragment: the reader's tag, Czech hints, Android's language detector, the first connected one. */
    fun language(ctx: Context, text: String, requested: String): String {
        val connected = connected(ctx)
        return ForeignLanguages.pick(text, requested, connected, if (connected.size > 1) detect(ctx, text) else emptyList())
            ?: requested.substringBefore('-')
    }
    /** On-device language identification (Android 10+), most likely first; empty when unavailable or unsure. */
    private fun detect(ctx: Context, text: String): List<String> = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 29 || text.count { it.isLetter() } < 12) return emptyList()
        val classifier = ctx.getSystemService(android.view.textclassifier.TextClassificationManager::class.java)?.textClassifier
            ?: return emptyList()
        val result = classifier.detectLanguage(android.view.textclassifier.TextLanguage.Request.Builder(text).build())
        (0 until result.localeHypothesisCount).map { result.getLocale(it) }
            .filter { result.getConfidenceScore(it) >= 0.5f }.map { it.toLanguageTag().substringBefore('-') }
    }.getOrDefault(emptyList())
    private fun resolve(ctx: Context, chosen: String, installed: List<Engine> = engines(ctx)): String? {
        if (chosen.isNotEmpty()) return installed.firstOrNull { it.id == chosen }?.id
        val default = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        return installed.firstOrNull { it.id == default }?.id ?: installed.firstOrNull { it.system }?.id
    }
    /** Engine for [language]: its own from the list, otherwise the one of the first connected language. */
    private fun engineFor(ctx: Context, language: String): String? {
        val entries = languages(ctx)
        val entry = entries.firstOrNull { it.language == language } ?: entries.firstOrNull() ?: return null
        return resolve(ctx, entry.engine)
    }
    fun available(ctx: Context) = connected(ctx).isNotEmpty()
    /** One connection per engine: alternating languages on different engines must not reconnect every fragment. */
    private class Client(val tts: TextToSpeech, val init: CompletableFuture<Int>)
    private val clients = HashMap<String, Client>()
    private var lastUse = 0L
    private val cooldown = HashMap<String, Long>()
    private val cache = LinkedHashMap<String, ByteArray>(16, .75f, true)
    private var cacheBytes = 0
    private val idle = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ForeignTtsIdle").apply { isDaemon = true } }
    init { idle.scheduleWithFixedDelay({ synchronized(this) {
        if (clients.isNotEmpty() && android.os.SystemClock.elapsedRealtime() - lastUse > 120000) closeClients()
    } }, 15, 15, TimeUnit.SECONDS) }
    fun reset() { idle.execute { synchronized(this) {
        closeClients(); cache.clear(); cacheBytes = 0; cooldown.clear()
    } } }
    @Synchronized fun clearAudioCache() { cache.clear(); cacheBytes=0 }
    private fun closeClients() { clients.values.forEach { runCatching { it.tts.shutdown() } }; clients.clear() }
    private fun closeClient(engine: String) { clients.remove(engine)?.let { runCatching { it.tts.shutdown() } } }
    private fun waitFor(future: CompletableFuture<*>, timeoutMs: Long) {
        val until = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (!future.isDone) {
            check(!SupertonicTTS.isCancelled()) { "Cancelled" }
            check(android.os.SystemClock.elapsedRealtime() < until) { "External TTS timeout" }
            try { future.get(50, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.TimeoutException) { }
        }
        future.get()
    }
    @Synchronized fun synthesize(ctx: Context, text: String, language: String, speed: Float,
        targetRate: Int, gain: Float): ByteArray? {
        val engine = engineFor(ctx, language) ?: return null
        lastUse = android.os.SystemClock.elapsedRealtime()
        val key = "$engine\u0000$language\u0000$speed\u0000$targetRate\u0000$gain\u0000$text"
        cache[key]?.let { Log.i("ForeignTTS", "Audio cache hit lang=$language chars=${text.length}"); return it }
        val providerKey = "$engine:$language"
        if (lastUse < (cooldown[providerKey] ?: 0)) return null
        var file: File? = null
        var sink: android.os.ParcelFileDescriptor? = null
        try {
            val connection = clients.getOrPut(engine) {
                val ready = CompletableFuture<Int>()
                Client(TextToSpeech(ctx.applicationContext, { status -> ready.complete(status) }, engine), ready)
            }
            waitFor(connection.init, 3000); check(connection.init.get() == TextToSpeech.SUCCESS) { "External TTS init failed" }
            val tts = connection.tts
            check(tts.setLanguage(Locale.forLanguageTag(language)) >= TextToSpeech.LANG_AVAILABLE) { "External TTS has no $language voice" }
            // Prefer installed offline voices; let the selected engine handle its own downloads/settings.
            tts.voices?.filter { it.locale.language == language && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
                ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.latency }.thenBy { it.name })
                ?.firstOrNull()?.let { tts.voice = it }
            tts.setSpeechRate(speed.coerceIn(.5f, 2.5f))
            val id = UUID.randomUUID().toString(); val done = CompletableFuture<Unit>()
            val audio = ByteArrayOutputStream(); var rate = 0; var format = 0; var channels = 0
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(Unit) }
                override fun onError(utteranceId: String?) { if (utteranceId == id) done.completeExceptionally(IllegalStateException("External TTS synthesis failed")) }
                override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                    if (utteranceId == id) { rate = sampleRateInHz; format = audioFormat; channels = channelCount }
                }
                override fun onAudioAvailable(utteranceId: String?, data: ByteArray?) {
                    if (utteranceId == id && data != null && !done.isDone) synchronized(audio) {
                        if (audio.size() + data.size > 8_000_000) done.completeExceptionally(IllegalStateException("External audio limit"))
                        else audio.write(data)
                    }
                }
            })
            val params = Bundle().apply { putBoolean("supertonic_foreign_proxy", true) }
            // PCM arrives through onAudioAvailable; the engine's WAV copy is never read.
            // Android 11+ accepts a descriptor, so send it to /dev/null instead of flash.
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                sink = android.os.ParcelFileDescriptor.open(File("/dev/null"), android.os.ParcelFileDescriptor.MODE_WRITE_ONLY)
                check(tts.synthesizeToFile(text, params, sink, id) == TextToSpeech.SUCCESS)
            } else {
                file = File.createTempFile("foreign-", ".wav", ctx.cacheDir)
                check(tts.synthesizeToFile(text, params, file, id) == TextToSpeech.SUCCESS)
            }
            waitFor(done, 8000)
            val source = synchronized(audio) { audio.toByteArray() }
            check(source.isNotEmpty()) { "External engine did not return audio chunks" }
            val pcm = ForeignPcm.convert(source, rate, channels, format == AudioFormat.ENCODING_PCM_FLOAT, targetRate, gain)
            check(format == AudioFormat.ENCODING_PCM_16BIT || format == AudioFormat.ENCODING_PCM_FLOAT)
            cache[key] = pcm; cacheBytes += pcm.size
            while (cacheBytes > 32_000_000 || cache.size > 128) {
                val first = cache.entries.first(); cacheBytes -= first.value.size; cache.remove(first.key)
            }
            Log.i("ForeignTTS", "Synthesized engine=$engine lang=$language chars=${text.length} audioMs=${pcm.size*500L/targetRate}")
            return pcm
        } catch (t: Exception) {
            clients[engine]?.tts?.stop(); closeClient(engine)
            if (!SupertonicTTS.isCancelled()) {
                cooldown[providerKey] = android.os.SystemClock.elapsedRealtime() + 60000
                Log.w("ForeignTTS", "External engine unavailable lang=$language: ${t.javaClass.simpleName}")
            }
            return null
        } finally { runCatching { sink?.close() }; file?.delete(); lastUse = android.os.SystemClock.elapsedRealtime() }
    }
}
