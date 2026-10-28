package com.brahmadeo.supertonic.tts.books

import android.content.Context
import java.io.File
import java.io.InputStream

/** Files that travel with a prepared book in its `.mytts-book` container, kept per text (content_sha256, so a
 * regeneration keeps them): the original EPUB/FB2 («Извлечь файл книги») and the location sounds. */
object BookFiles {
    private val SOURCE = Regex("[^/\\\\:*?\"<>|\\u0000-\\u001F]{1,120}\\.(epub|fb2|fb2\\.zip|zip)", RegexOption.IGNORE_CASE)
    /** l<number>_<variant>.m4a (location sounds), m<number>_<variant>.m4a (mood music) and e<number>.m4a (event sounds). */
    val SOUND = Regex("(l[0-9]{1,4}_[0-9]{1,2}|m[0-9]{1,3}_[0-9]{1,2}|e[0-9]{1,4})\\.m4a")
    private val SHA = Regex("[0-9a-f]{64}")

    fun dir(context: Context, sha: String): File {
        require(SHA.matches(sha)) { "Некорректный идентификатор книги" }
        return File(context.filesDir, "books/$sha")
    }
    fun ambience(context: Context, sha: String) = File(dir(context, sha), "ambience")

    /** The original book file, or null when the book was imported without it. */
    fun source(context: Context, sha: String): File? = dir(context, sha).listFiles()?.firstOrNull { it.isFile && it.name.startsWith("source.") }
    fun sourceName(context: Context, sha: String): String =
        runCatching { File(dir(context, sha), "source-name.txt").readText().trim() }.getOrDefault("").ifEmpty { "book.epub" }

    fun validSourceName(name: String) = SOURCE.matches(name)

    /** Saves the book file under its original [name] (atomically; the old one, if any, is replaced). */
    fun saveSource(context: Context, sha: String, name: String, input: InputStream, maxBytes: Long) {
        require(validSourceName(name)) { "Некорректное имя файла книги" }
        val dir = dir(context, sha).apply { mkdirs() }
        val ext = name.substringAfterLast('.').lowercase().let { if (name.lowercase().endsWith(".fb2.zip")) "fb2.zip" else it }
        val tmp = File(dir, "source.tmp")
        try {
            tmp.outputStream().use { out -> copyLimited(input, out, maxBytes) }
            dir.listFiles()?.filter { it.name.startsWith("source.") && it.name != "source.tmp" }?.forEach { it.delete() }
            check(tmp.renameTo(File(dir, "source.$ext"))) { "Не удалось сохранить файл книги" }
            File(dir, "source-name.txt").writeText(name)
        } finally { tmp.delete() }
    }

    fun saveSound(context: Context, sha: String, name: String, input: InputStream) {
        require(SOUND.matches(name)) { "Некорректное имя звука" }
        val dir = ambience(context, sha).apply { mkdirs() }
        val tmp = File(dir, "$name.tmp")
        try {
            tmp.outputStream().use { out -> copyLimited(input, out, MAX_SOUND_BYTES) }
            check(tmp.renameTo(File(dir, name))) { "Не удалось сохранить звук" }
        } finally { tmp.delete() }
    }

    fun sounds(context: Context, sha: String): List<File> =
        ambience(context, sha).listFiles()?.filter { it.isFile && SOUND.matches(it.name) }?.sortedBy { it.name }.orEmpty()

    /** The sound of event [id] (e<N>), or null when it was not made. */
    fun event(context: Context, sha: String, id: String): File? = File(ambience(context, sha), "$id.m4a").takeIf { it.isFile }

    /** Sound variants of one location, in order. */
    fun sounds(context: Context, sha: String, location: String): List<File> =
        sounds(context, sha).filter { it.name.startsWith("${location}_") }

    fun remove(context: Context, sha: String) { runCatching { dir(context, sha).deleteRecursively() } }

    const val MAX_SOUND_BYTES = 4L * 1024 * 1024

    private fun copyLimited(input: InputStream, out: java.io.OutputStream, max: Long) {
        val buf = ByteArray(65536); var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n; require(total <= max) { "Файл в книге слишком велик" }
            out.write(buf, 0, n)
        }
    }
}
