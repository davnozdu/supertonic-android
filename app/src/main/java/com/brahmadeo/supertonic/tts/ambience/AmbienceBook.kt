package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import android.util.Log
import com.brahmadeo.supertonic.tts.books.BookFiles
import com.brahmadeo.supertonic.tts.books.SceneIndex
import org.json.JSONObject
import java.io.File

/** The sounds of one book's locations: [variants] clips of [SECONDS] s per location (the player alternates them with
 * crossfades), loudness-normalised to [Loudness.TARGET_LUFS], AAC 64 kbit/s in BookFiles.ambience. sounds.json records
 * the prompt each location was made from, so a re-prepared book regenerates only the places that changed. */
object AmbienceBook {
    const val SECONDS = 30f
    private const val BITRATE = 64_000
    private const val LUFS_KEY = "_lufs"

    fun variants(locations: Int) = if (locations <= 21) 3 else 2

    private fun manifest(context: Context, sha: String) = File(BookFiles.ambience(context, sha), "sounds.json")
    private fun readManifest(context: Context, sha: String): JSONObject =
        runCatching { JSONObject(manifest(context, sha).readText()) }.getOrDefault(JSONObject())

    /** Drops sounds of places that are gone or whose prompt changed. Sounds without a manifest came inside the
     * book file (made for exactly these locations) and are kept. */
    fun sync(context: Context, sha: String, scenes: SceneIndex) {
        val files = BookFiles.sounds(context, sha)
        if (files.isEmpty()) return
        val known = readManifest(context, sha)
        if (known.length() == 0) return
        // Made at another loudness (before −14 LUFS): all of them again.
        if (known.optDouble(LUFS_KEY, -20.0) != Loudness.TARGET_LUFS) { files.forEach { it.delete() }; manifest(context, sha).delete(); return }
        val current = scenes.locations.associate { it.id to it.sound }
        for (f in files) {
            val id = f.name.substringBefore('_') // l3_0.m4a -> l3
            if (current[id] == null || known.optString(id) != current[id]) f.delete()
        }
    }

    /** Locations with a clip still to make. */
    fun missing(context: Context, sha: String, scenes: SceneIndex): List<SceneIndex.Location> {
        sync(context, sha, scenes)
        val n = variants(scenes.locations.size)
        val have = BookFiles.sounds(context, sha).map { it.name }.toSet()
        return scenes.locations.filter { loc -> (0 until n).any { "${loc.id}_$it.m4a" !in have } }
    }

    /** Generates the missing clips (one at a time; NPU when switched on). [progress]: (done, total, place). */
    fun generate(context: Context, sha: String, scenes: SceneIndex, threads: Int = 4,
                 progress: (Int, Int, String) -> Unit = { _, _, _ -> }, cancelled: () -> Boolean = { false }) {
        require(AmbienceModel.ready(context)) { "Скачайте генератор фоновых звуков в разделе «Фоновая музыка»" }
        sync(context, sha, scenes)
        val n = variants(scenes.locations.size)
        val dir = BookFiles.ambience(context, sha).apply { mkdirs() }
        val todo = scenes.locations.flatMap { loc -> (0 until n).map { loc to it } }.filter { (loc, k) -> !File(dir, "${loc.id}_$k.m4a").isFile }
        if (todo.isEmpty()) return
        val npu = AmbienceModel.npuDit(context)
        try {
            val generator = Sa3Generator(AmbienceModel.root(context), threads, npu)
            val manifest = readManifest(context, sha)
            for ((done, pair) in todo.withIndex()) {
                if (cancelled()) throw InterruptedException("cancelled")
                val (loc, k) = pair
                progress(done, todo.size, loc.name)
                val seed = loc.sound.hashCode().toLong() * 31 + k
                val pcm = generator.generate(loc.sound, SECONDS, seed, cancelled).pcm
                val gain = Loudness.normalize(pcm, Sa3Generator.SAMPLE_RATE)
                AacFile.write(File(dir, "${loc.id}_$k.m4a"), pcm, bitrate = BITRATE)
                manifest.put(loc.id, loc.sound).put(LUFS_KEY, Loudness.TARGET_LUFS)
                manifest(context, sha).writeText(manifest.toString())
                Log.i("Ambience", "Sound ${loc.id}_$k ready (gain ${"%.1f".format(gain)} dB, npu=${npu != null})")
            }
            progress(todo.size, todo.size, "")
        } finally { npu?.close() }
    }
}
