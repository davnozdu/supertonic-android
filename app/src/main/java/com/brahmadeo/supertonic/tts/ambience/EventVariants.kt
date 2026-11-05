package com.brahmadeo.supertonic.tts.ambience

import java.io.File

/** Numbered bank variants; no fixed limit on the number of clips. */
object EventVariants {
    fun files(base: File): List<File> {
        val stem = base.nameWithoutExtension
        return base.parentFile?.listFiles()?.mapNotNull { f ->
            val n = if (f.name == base.name) 1 else if (f.name.startsWith("${stem}_") && f.extension == "m4a")
                f.nameWithoutExtension.removePrefix("${stem}_").toIntOrNull()?.takeIf { it >= 2 } else null
            if (n != null && n >= 1 && f.isFile && f.length() > 0) n to f else null
        }?.sortedBy { it.first }?.map { it.second }.orEmpty()
    }

    fun id(stem: String, known: Set<String>): String {
        if (stem in known) return stem
        val suffix = stem.substringAfterLast('_').toIntOrNull() ?: return stem
        return if (suffix >= 2) stem.substringBeforeLast('_') else stem
    }
}
