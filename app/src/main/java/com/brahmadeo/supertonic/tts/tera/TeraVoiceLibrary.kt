package com.brahmadeo.supertonic.tts.tera

import android.content.Context
import com.brahmadeo.supertonic.tts.books.BookVoiceRef
import com.brahmadeo.supertonic.tts.books.VoiceGenderSettings
import com.brahmadeo.supertonic.tts.utils.AssetManager
import com.brahmadeo.supertonic.tts.utils.ResumableModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Extra Tera voices downloaded on request from our GitHub release tera-voices-v1, each one checked
 * by listening tests on the phone before it was listed (assets/tera_voice_library.json pins sizes
 * and SHA-256). Kept apart from the built-in and the imported voices, in tera/library/styles/lib_<id>/
 * (the engine wants the voice folder inside a "styles" folder).
 */
object TeraVoiceLibrary {
    const val PREFIX = "lib_"
    private const val BASE = "https://github.com/davnozdu/supertonic-android/releases/download/tera-voices-v1"
    private val lock = Mutex()
    data class Voice(val id: String, val title: String, val gender: String, val ttl: Long, val ttlSha: String, val dp: Long, val dpSha: String)

    fun root(context: Context) = File(context.filesDir, "${AssetManager.MODEL_VERSION}/tera/library/styles")
    fun isLibrary(name: String) = name.startsWith(PREFIX)

    @Volatile private var cached: List<Voice>? = null
    fun voices(context: Context): List<Voice> = cached ?: run {
        val rows = JSONObject(context.assets.open("tera_voice_library.json").bufferedReader().use { it.readText() }).getJSONArray("voices")
        List(rows.length()) { i -> rows.getJSONObject(i).let {
            Voice(it.getString("id"), it.getString("title"), it.getString("gender"),
                it.getLong("ttl_size"), it.getString("ttl_sha256"), it.getLong("dp_size"), it.getString("dp_sha256"))
        } }.also { cached = it }
    }

    private fun complete(context: Context, v: Voice) =
        File(root(context), "$PREFIX${v.id}/style_ttl.npy").length() == v.ttl && File(root(context), "$PREFIX${v.id}/style_dp.npy").length() == v.dp

    /** Downloaded voices, as engine voice names ("lib_<id>"). */
    fun installed(context: Context): List<String> =
        runCatching { voices(context).filter { complete(context, it) }.map { PREFIX + it.id } }.getOrDefault(emptyList())

    /** Not downloaded and not removed by the user (a removed one comes back via «Вернуть скрытые голоса»). */
    fun missing(context: Context): List<Voice> = runCatching {
        val removed = com.brahmadeo.supertonic.tts.utils.HiddenVoices.hidden(context, AssetManager.TERA_MODEL)
        voices(context).filterNot { complete(context, it) || PREFIX + it.id in removed }
    }.getOrDefault(emptyList())

    /** Title from the manifest (loaded by the voice list before any label is asked for). */
    /** "m" / "f" from the manifest (pitch measured on the phone; Smooth Cassius by ear), null if unknown or not loaded. */
    fun gender(name: String): String? = cached?.firstOrNull { PREFIX + it.id == name }?.gender?.takeIf { it == "m" || it == "f" }

    fun label(name: String, withGender: Boolean = true): String {
        val v = cached?.firstOrNull { PREFIX + it.id == name }
        // Gender measured on the phone (pitch); unknown ones get no mark rather than a guess.
        val sex = if (!withGender) "" else when (v?.gender) { "m" -> " (муж)"; "f" -> " (жен)"; else -> "" }
        return "Tera+ · " + (v?.title ?: name.removePrefix(PREFIX).replace('_', ' ')) + sex
    }

    suspend fun download(context: Context, progress: (String, Float) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            val todo = missing(context)
            val total = todo.sumOf { it.ttl + it.dp }.coerceAtLeast(1)
            var done = 0L
            for (v in todo) {
                val dir = File(root(context), "$PREFIX${v.id}").apply { mkdirs() }
                ResumableModelFile.fetch("$BASE/${v.id}_style_dp.npy", File(dir, "style_dp.npy"), v.dp, v.dpSha) { r, _ ->
                    progress("Голоса Tera: ${v.title}", (done + r).toFloat() / total)
                }
                done += v.dp
                ResumableModelFile.fetch("$BASE/${v.id}_style_ttl.npy", File(dir, "style_ttl.npy"), v.ttl, v.ttlSha) { r, _ ->
                    progress("Голоса Tera: ${v.title}", (done + r).toFloat() / total)
                }
                done += v.ttl
                val key = BookVoiceRef(AssetManager.TERA_MODEL, PREFIX + v.id).key
                if (v.gender in listOf("m", "f") && context.getSharedPreferences("voice_genders", 0).getString(key, null) == null)
                    VoiceGenderSettings.save(context, key, v.gender)
            }
            progress("Голоса Tera готовы: ${voices(context).size}", 1f)
        }
    }

    fun deleteAll(context: Context) { root(context).deleteRecursively() }
}
