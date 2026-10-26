package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import java.io.File

/** «Выравнивать темп голосов»: every voice has its own natural tempo, and with several voices (author, roles, book
 * characters, mixed models) the same speed made one read slowly and another fast. Each voice is measured once on the
 * same calibration phrase (speech only, silence trimmed) and its speed is scaled to the tempo of the selected voice.
 * The measurement is kept per voice file and package variant; nothing is re-measured while reading. */
object VoiceTempo {
    const val KEY = "voice_tempo_equalize"
    const val PHRASE = "Сегодня утром мы долго шли по тихой улице и разговаривали о новых книгах."
    private const val STORE = "voice_tempo"
    private const val MIN = .67f
    private const val MAX = 1.5f

    fun enabled(ctx: Context) = ctx.getSharedPreferences("SupertonicPrefs", 0).getBoolean(KEY, true)

    /** The selected voice of the selected model: the tempo every other voice is brought to. */
    fun reference(ctx: Context): String? = runCatching {
        val name = ctx.getSharedPreferences("SupertonicPrefs", 0).getString("selected_voice", null) ?: return null
        AssetManager.voiceFile(ctx, name).canonicalPath
    }.getOrNull()

    /** [speed] for [stylePath], scaled to the reference voice's tempo. [measure] synthesizes a phrase with a voice at
     * speed 1 and returns PCM16 at [rate] Hz (null when it cannot). */
    fun speed(ctx: Context, stylePath: String, speed: Float, rate: Int, measure: (String, String) -> ByteArray?): Float {
        if (!enabled(ctx)) return speed
        val ref = reference(ctx) ?: return speed
        val voice = runCatching { File(stylePath).canonicalPath }.getOrDefault(stylePath)
        if (voice == ref) return speed
        val own = speechMs(ctx, voice, rate, measure) ?: return speed
        val target = speechMs(ctx, ref, rate, measure) ?: return speed
        return speed * factor(own, target)
    }

    /** A voice that needs [own] ms for the phrase against [target] ms is sped up (or slowed) by their ratio. */
    fun factor(own: Long, target: Long): Float =
        if (own <= 0 || target <= 0) 1f else (own.toFloat() / target).coerceIn(MIN, MAX)

    private fun speechMs(ctx: Context, voice: String, rate: Int, measure: (String, String) -> ByteArray?): Long? {
        val prefs = ctx.getSharedPreferences(STORE, 0)
        val p = ctx.getSharedPreferences("SupertonicPrefs", 0)
        // Tera's tempo comes from its duration predictor: the sampler (fast / int8 / teacher) and its noise do not change
        // it, so switching them must not re-measure every voice.
        val key = "$voice|${p.getBoolean("kokoro_full_precision", true)}|${p.getBoolean("shtorm_slow", false)}"
        prefs.getLong(key, 0L).takeIf { it > 0 }?.let { return it }
        // Built-in and library voices come measured with the app: nothing to synthesize on the phone.
        preset(ctx, voice, p)?.let { return it }
        val pcm = runCatching { measure(voice, PHRASE) }.getOrNull() ?: return null
        val ms = speechDurationMs(pcm, rate).takeIf { it > 300 } ?: return null
        prefs.edit().putLong(key, ms).apply()
        android.util.Log.i("VoiceTempo", "Measured ${File(voice).name} speech=${ms}ms")
        return ms
    }

    @Volatile private var presets: Map<String, Long>? = null
    /** assets/voice_tempo_presets.json: "path under filesDir|variant" → speech ms, measured on the phone with the app's
     * own models (variant: Kokoro "full", Shtorm "slow", otherwise empty). */
    private fun preset(ctx: Context, voice: String, p: android.content.SharedPreferences): Long? = runCatching {
        val table = presets ?: org.json.JSONObject(ctx.assets.open("voice_tempo_presets.json").bufferedReader().use { it.readText() })
            .getJSONObject("voices").let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }.also { presets = it }
        val rel = File(voice).canonicalFile.relativeToOrNull(ctx.filesDir.canonicalFile)?.invariantSeparatorsPath ?: return null
        val variant = when {
            rel.startsWith("kokoro-ru-v2/") -> if (p.getBoolean("kokoro_full_precision", true)) "full" else ""
            rel.startsWith("shtorm-pocket-v2/") -> if (p.getBoolean("shtorm_slow", false)) "slow" else ""
            else -> ""
        }
        table["$rel|$variant"]
    }.getOrNull()

    /** Milliseconds from the first to the last frame within 40 dB of the loudest one (10 ms frames). */
    fun speechDurationMs(pcm: ByteArray, rate: Int): Long {
        val frame = maxOf(1, rate / 100)
        val n = pcm.size / 2 / frame
        if (n == 0) return 0
        val rms = DoubleArray(n) { f ->
            var sum = 0.0
            for (i in 0 until frame) {
                val k = (f * frame + i) * 2
                val s = ((pcm[k].toInt() and 255) or (pcm[k + 1].toInt() shl 8)).toShort().toDouble()
                sum += s * s
            }
            Math.sqrt(sum / frame)
        }
        val peak = rms.maxOrNull() ?: return 0
        if (peak <= 0) return 0
        val floor = peak / 100.0
        val first = rms.indexOfFirst { it >= floor }
        val last = rms.indexOfLast { it >= floor }
        return if (first < 0) 0 else (last - first + 1) * 10L
    }
}
