package com.brahmadeo.supertonic.tts.ambience

/** Exact request and variant identify work; moods with the same request do not need new generation. */
internal object MusicReuse {
    data class Clip(val file: String, val prompt: String, val seconds: Float)
    fun duplicates(clips: List<Clip>): Map<String, List<String>> {
        return clips.groupBy { Triple(it.prompt, it.seconds, it.file.substringBeforeLast('.').substringAfterLast('_')) }
            .values.filter { it.size > 1 }.associate { group -> group.first().file to group.drop(1).map { it.file } }
    }
}
