package com.brahmadeo.supertonic.tts.books.prepare

import java.io.File

/** A queued source is an immediate child of one entry under our queue, never a substring match in another path. */
internal object BookQueuePath {
    fun directory(root: File, source: File): File? = runCatching {
        val file = source.canonicalFile
        file.parentFile?.takeIf { it.parentFile == root.canonicalFile }
    }.getOrNull()
}
