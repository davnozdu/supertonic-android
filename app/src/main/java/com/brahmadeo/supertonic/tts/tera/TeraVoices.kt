package com.brahmadeo.supertonic.tts.tera

import android.content.Context
import com.brahmadeo.supertonic.tts.utils.AssetManager
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** All ten pinned reference styles are tiny enough to ship with the APK.
 * No model re-download or network is required when upgrading from four voices. */
object TeraVoices {
    val names = listOf("ru_f1", "ru_f2", "ru_m1", "ru_m5", "eng_f3", "eng_f4_whisper", "eng_f5", "eng_m2_whisper", "eng_m3", "eng_m4")
    private data class Style(val voice: String, val part: String, val size: Long, val sha: String)
    private var checkedRoot: String? = null
    private var styles: List<Style>? = null
    fun label(name: String) = when {
        name.endsWith("_whisper") -> "$name · шёпот, русский текст"
        name.startsWith("eng_") -> "$name · английский образец, русский текст"
        else -> name
    }
    // The model author recommends 0.8 as a starting point for English references speaking Russian.
    fun durationScale(stylePath: String) = if (File(stylePath).parentFile?.name?.startsWith("eng_") == true) .8f else 1f
    @Synchronized fun ensureInstalled(context: Context) {
        val root = File(context.filesDir, "${AssetManager.MODEL_VERSION}/tera/styles")
        val files = styles ?: JSONObject(context.assets.open("tera_styles/manifest.json").bufferedReader().use { it.readText() }).getJSONArray("files").let { rows ->
            List(rows.length()) { i -> rows.getJSONObject(i).let { row ->
                Style(row.getString("voice"),row.getString("part"),row.getLong("size"),row.getString("sha256"))
            } }.also { require(it.size == names.size * 2 && it.all { file -> file.voice in names && file.part in listOf("style_dp","style_ttl") }) }
        }.also { styles = it }
        if (checkedRoot == root.path && files.all { File(root,"${it.voice}/${it.part}.npy").length() == it.size }) return
        for (style in files) {
            val target = File(root,"${style.voice}/${style.part}.npy")
            if (target.length() == style.size && sha(target.readBytes()) == style.sha) continue
            val bytes = context.assets.open("tera_styles/${style.voice}_${style.part}.npy").use { it.readBytes() }
            check(bytes.size.toLong() == style.size && sha(bytes) == style.sha) { "Invalid bundled Tera style" }
            target.parentFile!!.mkdirs()
            val temporary = File(target.path + ".bundled")
            temporary.writeBytes(bytes)
            check(temporary.renameTo(target)) { "Cannot install Tera style" }
        }
        checkedRoot = root.path
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
