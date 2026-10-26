package com.brahmadeo.supertonic.tts.tera

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.brahmadeo.supertonic.tts.utils.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The user's own Tera voices (timbres), imported from files: a Supertonic-style .json
 * ({"style_ttl": {"dims": [1,50,256], "data": …}, "style_dp": {"dims": [1,8,16], …}}) or the two
 * TeraTTS files style_ttl.npy + style_dp.npy chosen together. Idea and checks follow the
 * TeraTTS fork (github.com/9208499-ship-it/teratts-android, GPL-3.0, VoiceImport.kt); stored in
 * our layout: tera/styles/my_<name>_<hash>/style_{ttl,dp}.npy, float32 little-endian, next to the
 * built-in voices. The content hash in the folder name keeps the audio caches (keyed by the
 * voice path) and the engine's style cache from ever mixing two different timbres.
 */
object TeraCustomVoices {
    const val PREFIX = "my_"
    private val TTL = intArrayOf(1, 50, 256)
    private val DP = intArrayOf(1, 8, 16)

    class Failure(message: String) : Exception(message)

    private fun root(context: Context) = File(context.filesDir, "${AssetManager.MODEL_VERSION}/tera/styles")

    fun isCustom(name: String) = name.startsWith(PREFIX)

    /** Imported voices that have both style files of the right size. */
    fun installed(context: Context): List<String> =
        root(context).listFiles { f -> f.isDirectory && isCustom(f.name) }.orEmpty()
            .filter { File(it, "style_ttl.npy").length() == npyBytes(12800) && File(it, "style_dp.npy").length() == npyBytes(128) }
            .map { it.name }.sorted()

    /** "my_gravel_pete_1a2b3c" → "Свой · gravel pete". */
    fun label(name: String) = "Свой · " + name.removePrefix(PREFIX).replace(Regex("_[0-9a-f]{6}$"), "").replace('_', ' ')

    fun delete(context: Context, name: String) {
        require(isCustom(name) && '/' !in name && ".." !in name)
        File(root(context), name).deleteRecursively()
    }

    /** [gender] "m"/"f" when the file's metadata says so (Supertonic voice packs carry it). */
    data class Imported(val name: String, val gender: String?)

    /** Adds the voice. Throws [Failure] with a reason. */
    fun import(context: Context, uris: List<Uri>): Imported {
        if (uris.isEmpty()) throw Failure("файлы не выбраны")
        val named = uris.map { nameOf(context, it) to it }
        fun read(u: Uri) = context.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: throw Failure("файл не читается")
        val json = named.firstOrNull { it.first.endsWith(".json", true) }
        var gender: String? = null
        val (ttl, dp, base) = if (json != null) {
            val o = try { JSONObject(read(json.second).toString(Charsets.UTF_8)) } catch (e: Exception) { throw Failure("это не JSON голоса") }
            val meta = o.optJSONObject("metadata")
            gender = when (meta?.optString("gender")?.lowercase()) { "male", "m" -> "m"; "female", "f" -> "f"; else -> null }
            // The file name is what the user chose; metadata names are often a default ("Мой голос").
            val stem = json.first.substringBeforeLast('.')
            val title = stem.takeIf { Regex("""\p{L}""").containsMatchIn(it) && !it.startsWith("primary:") }
                ?: meta?.optString("name")?.takeIf { it.isNotBlank() } ?: "voice"
            Triple(component(o.optJSONObject("style_ttl"), TTL, "style_ttl"), component(o.optJSONObject("style_dp"), DP, "style_dp"), title)
        } else {
            val t = named.firstOrNull { it.first.contains("ttl", true) && it.first.endsWith(".npy", true) }
            val d = named.firstOrNull { it.first.contains("dp", true) && it.first.endsWith(".npy", true) }
            if (t == null || d == null) throw Failure("выберите .json голоса или оба файла: style_ttl.npy и style_dp.npy")
            val a = try { readNpy(read(t.second), TTL) } catch (e: Exception) { throw Failure("style_ttl.npy: ${e.message}") }
            val b = try { readNpy(read(d.second), DP) } catch (e: Exception) { throw Failure("style_dp.npy: ${e.message}") }
            // The voice's folder name is a better name than "style_ttl".
            val folder = uris.first().pathSegments.dropLast(1).lastOrNull()?.substringAfterLast('/')?.takeIf { it.isNotBlank() && ':' !in it }
            Triple(a, b, folder ?: "voice")
        }
        if ((ttl + dp).any { !it.isFinite() }) throw Failure("в голосе есть NaN или бесконечность")
        val ttlBytes = npy(ttl, TTL)
        val dpBytes = npy(dp, DP)
        val hash = MessageDigest.getInstance("SHA-256").digest(ttlBytes + dpBytes).take(3).joinToString("") { "%02x".format(it.toInt() and 255) }
        val clean = base.replace(Regex("""[^\p{L}\p{N}]+"""), "_").trim('_').removePrefix(PREFIX).take(32).ifEmpty { "voice" }
        val dir = File(root(context), "$PREFIX${clean}_$hash")
        val temporary = File(root(context), ".import-${dir.name}")
        temporary.deleteRecursively()
        temporary.mkdirs()
        File(temporary, "style_ttl.npy").writeBytes(ttlBytes)
        File(temporary, "style_dp.npy").writeBytes(dpBytes)
        dir.deleteRecursively() // same hash = same timbre
        if (!temporary.renameTo(dir)) { temporary.deleteRecursively(); throw Failure("не удалось сохранить голос") }
        return Imported(dir.name, gender)
    }

    private fun nameOf(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "voice"
    }

    /** One style tensor from the Supertonic JSON layout, size checked against its dims. */
    internal fun component(o: JSONObject?, want: IntArray, what: String): FloatArray {
        if (o == null) throw Failure("нет «$what»")
        val dims = o.optJSONArray("dims") ?: throw Failure("у «$what» нет dims")
        val got = IntArray(dims.length()) { dims.getInt(it) }
        if (!got.contentEquals(want)) throw Failure("«$what» размером ${got.joinToString("×")}, нужен ${want.joinToString("×")}")
        val n = want.fold(1) { a, b -> a * b }
        val out = FloatArray(n)
        var i = 0
        fun walk(a: JSONArray) {
            for (k in 0 until a.length()) {
                val x = a.get(k)
                if (x is JSONArray) walk(x)
                else { if (i >= n) throw Failure("в «$what» больше $n чисел"); out[i++] = (x as? Number ?: throw Failure("в «$what» не число")).toFloat() }
            }
        }
        walk(o.optJSONArray("data") ?: throw Failure("у «$what» нет data"))
        if (i != n) throw Failure("в «$what» $i чисел вместо $n")
        return out
    }

    /** Minimal .npy reader: little-endian float32/float64, C order, shape must equal [want]. */
    internal fun readNpy(bytes: ByteArray, want: IntArray): FloatArray {
        require(bytes.size > 10 && bytes[0] == 0x93.toByte() && String(bytes, 1, 5, Charsets.US_ASCII) == "NUMPY") { "не файл .npy" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val (length, start) = if (bytes[6].toInt() == 1) (bb.getShort(8).toInt() and 0xFFFF) to 10 else bb.getInt(8) to 12
        val header = String(bytes, start, length, Charsets.US_ASCII)
        require(!Regex("""'fortran_order':\s*True""").containsMatchIn(header)) { "порядок Fortran не поддерживается" }
        val descr = Regex("""'descr':\s*'([^']+)'""").find(header)?.groupValues?.get(1) ?: error("нет descr")
        val shape = Regex("""'shape':\s*\(([^)]*)\)""").find(header)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { it.toInt() }?.toIntArray() ?: error("нет shape")
        require(shape.contentEquals(want)) { "размер ${shape.joinToString("×")}, нужен ${want.joinToString("×")}" }
        val n = want.fold(1) { a, b -> a * b }
        bb.position(start + length)
        return when (descr) {
            "<f4" -> FloatArray(n) { bb.float }
            "<f8" -> FloatArray(n) { bb.double.toFloat() }
            else -> error("тип $descr не поддерживается, нужен float32 или float64")
        }
    }

    /** float32 little-endian .npy, version 1.0 — the form TeraEngine.loadNpy reads. */
    internal fun npy(data: FloatArray, shape: IntArray): ByteArray {
        val header = npyHeader(shape)
        val bb = ByteBuffer.allocate(header.size + data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(header)
        data.forEach { bb.putFloat(it) }
        return bb.array()
    }

    private fun npyHeader(shape: IntArray): ByteArray {
        var dict = "{'descr': '<f4', 'fortran_order': False, 'shape': (${shape.joinToString(", ")}), }"
        while ((10 + dict.length + 1) % 64 != 0) dict += " "
        dict += "\n"
        val bytes = dict.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x93.toByte()) + "NUMPY".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 0, (bytes.size and 255).toByte(), (bytes.size shr 8).toByte()) + bytes
    }

    private fun npyBytes(floats: Int) = 128L + floats * 4L
}
