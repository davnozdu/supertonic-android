package com.brahmadeo.supertonic.tts.ambience

import android.content.Context
import com.brahmadeo.supertonic.tts.music.GeneratedMusic
import com.brahmadeo.supertonic.tts.music.MusicCatalog
import org.json.JSONObject
import java.io.File

/** Search already prepared/generated music and explicitly described catalog tracks before online lookup. */
internal object MusicBank {
    private data class Candidate(val file: File, val prompt: String, val ready: Boolean)
    fun find(context: Context, prompt: String, salt: Int, online: Boolean, cancelled: () -> Boolean): File? {
        val candidates = mutableListOf<Candidate>()
        GeneratedMusic.list(context).forEach { candidates += Candidate(it.file, MusicEnhance.prompt(it.prompt), true) }
        // Existing book music has an exact reconstruction of the intensity-specific prompt.
        File(context.filesDir, "books").listFiles()?.filter { it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}")) }?.forEach bookLoop@ { book ->
            if (cancelled()) throw InterruptedException("cancelled")
            val dir = File(book, "ambience")
            val manifest = runCatching { JSONObject(File(dir, "sounds.json").readText()) }.getOrNull() ?: return@bookLoop
            if (manifest.optInt("_music") != 2) return@bookLoop
            dir.listFiles()?.filter { it.name.matches(Regex("m[0-9]{1,3}_[0-9]{1,2}\\.m4a")) && it.length() > 0 }?.forEach fileLoop@ { file ->
                val recipe = manifest.optString(file.name.substringBefore('_'))
                if (recipe.isBlank()) return@fileLoop
                val variant = file.nameWithoutExtension.substringAfterLast('_').toIntOrNull() ?: return@fileLoop
                val raw = if (recipe.endsWith(" | tension-v1")) AmbienceBook.intensity(recipe.removeSuffix(" | tension-v1"), variant) else recipe
                candidates += Candidate(file, MusicEnhance.prompt(raw), true)
            }
        }
        // Titles such as "Stillness of Ink" do not establish mood or instrumentation. Untagged tracks are skipped.
        MusicCatalog.tracks(context).filter { it.prompt.isNotBlank() }.forEach { track ->
            MusicCatalog.ready(context, track)?.let { candidates += Candidate(it, track.prompt, false) }
        }
        val exact = candidates.filter { MusicMatch.exact(prompt, it.prompt) }
        val matching = exact.ifEmpty { candidates.filter { MusicMatch.matches(prompt, "instrumental music", it.prompt, listOf("instrumental")) } }
        if (matching.isNotEmpty()) {
            val choice = matching[Math.floorMod(salt, matching.size)]
            if (choice.ready) return choice.file
            // Catalog MP3 is converted once; source remains the verified installed catalog track.
            val target = File(context.filesDir, "music-bank/${choice.file.nameWithoutExtension}.m4a")
            if (target.isFile && target.length() > 0) return target
            target.parentFile?.mkdirs()
            try {
                FreesoundAudio.prepare(choice.file, target, false, 30.0, music = true, clipSeconds = 30, cancelled = cancelled)
                return target
            } catch (e: InterruptedException) { throw e }
            catch (_: Exception) { target.delete(); File(target.path + ".part").delete() }
        }
        return FreesoundBank.find(context, "", prompt, event = false, allowNetwork = online, cancelled = cancelled, music = true)
    }
}
