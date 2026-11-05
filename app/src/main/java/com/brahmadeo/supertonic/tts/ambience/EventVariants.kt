package com.brahmadeo.supertonic.tts.ambience

import java.io.File

/** Numbered bank variants; no fixed limit on the number of clips. */
object EventVariants {
    fun files(base: File): List<File> {
        val stem = base.nameWithoutExtension
        val pattern = Regex("${Regex.escape(stem)}_(\\d+)\\.m4a")
        return base.parentFile?.listFiles()?.mapNotNull { f ->
            val n = if (f.name == base.name) 1 else pattern.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
            if (n != null && n >= 1 && f.isFile && f.length() > 0) n to f else null
        }?.sortedBy { it.first }?.map { it.second }.orEmpty()
    }

    fun id(stem: String, known: Set<String>): String {
        if (stem in known) return stem
        val suffix = stem.substringAfterLast('_').toIntOrNull() ?: return stem
        return if (suffix >= 2) stem.substringBeforeLast('_') else stem
    }
}
