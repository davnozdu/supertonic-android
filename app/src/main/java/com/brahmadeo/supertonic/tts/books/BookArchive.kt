package com.brahmadeo.supertonic.tts.books

import android.content.Context
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** The `.mytts-book` file: an ordinary ZIP (any archiver opens it) with
 *  - book.json — the prepared book ([BookPackage]: characters, fingerprints, replies, locations, «Умная книга»);
 *  - source/<name>.epub|.fb2 — the original book file (optional);
 *  - ambience/l<N>_<k>.m4a — the location sounds (optional).
 * Deflate at the lowest level: book.json shrinks several times, the sounds and the book file are compressed already.
 * A plain JSON `.mytts-book` (older versions) is still imported. */
object BookArchive {
    const val MAX_BYTES = 512L * 1024 * 1024
    private const val MAX_SOURCE = 64L * 1024 * 1024
    private const val MAX_SOUNDS = 400

    fun isZip(file: File) = file.inputStream().use { val b = ByteArray(2); it.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }

    /** Imports [file] (a copy owned by the caller) and returns the book id. */
    fun import(context: Context, file: File): Long {
        require(file.length() in 1..MAX_BYTES) { "Файл книги больше ${MAX_BYTES / 1024 / 1024} МБ" }
        if (!isZip(file)) {
            require(file.length() <= BookPackage.MAX_BYTES) { "Файл подготовленной книги слишком велик" }
            return BookLibrary.import(context, file.readText())
        }
        ZipFile(file).use { zip ->
            val entry = requireNotNull(zip.getEntry("book.json")) { "Это не файл подготовленной книги MyTTS" }
            require(entry.size in 1..BookPackage.MAX_BYTES) { "Файл подготовленной книги слишком велик" }
            val json = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            val id = BookLibrary.import(context, json)
            val sha = requireNotNull(BookLibrary.contentSha(context, id))
            val entries = zip.entries().toList().filter { !it.isDirectory }
            // The book is imported either way; a book file or a sound that cannot be kept is only logged.
            entries.firstOrNull { it.name.startsWith("source/") }?.let { e ->
                val name = e.name.removePrefix("source/")
                if (BookFiles.validSourceName(name)) runCatching { zip.getInputStream(e).use { BookFiles.saveSource(context, sha, name, it, MAX_SOURCE) } }
                    .onFailure { android.util.Log.w("BookArchive", "Book file not kept: ${it.javaClass.simpleName}") }
            }
            entries.firstOrNull { it.name == "prepared/text.json.gz" }?.let { e ->
                runCatching {
                    val target = com.brahmadeo.supertonic.tts.books.PreparedText.file(context, sha)
                    target.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
                }.onFailure { android.util.Log.w("BookArchive", "Prepared text not kept: ${it.javaClass.simpleName}") }
            }
            val sounds = entries.filter { it.name.startsWith("ambience/") && BookFiles.SOUND.matches(it.name.removePrefix("ambience/")) }.take(MAX_SOUNDS)
            for (e in sounds) runCatching { zip.getInputStream(e).use { BookFiles.saveSound(context, sha, e.name.removePrefix("ambience/"), it) } }
                .onFailure { android.util.Log.w("BookArchive", "Sound not kept: ${it.javaClass.simpleName}") }
            BookLibrary.get(context, id)?.let { com.brahmadeo.supertonic.tts.ambience.AmbienceBook.adopt(context, sha, it.scenes) }
            return id
        }
    }

    /** The container of book [id] for «Поделиться», written to [target]. */
    fun export(context: Context, id: Long, target: File): Boolean {
        val json = BookLibrary.export(context, id) ?: return false
        val sha = BookLibrary.contentSha(context, id) ?: return false
        val tmp = File(target.path + ".tmp")
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)
                zip.putNextEntry(ZipEntry("book.json")); zip.write(json.toByteArray()); zip.closeEntry()
                BookFiles.source(context, sha)?.let { src ->
                    zip.putNextEntry(ZipEntry("source/${BookFiles.sourceName(context, sha)}")); src.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
                for (s in BookFiles.sounds(context, sha)) {
                    zip.putNextEntry(ZipEntry("ambience/${s.name}")); s.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
                // Готовый текст книги (ударения, ё, пунктуация): с ним книга читается без нейросети и без интернета.
                val prepared = com.brahmadeo.supertonic.tts.books.PreparedText.file(context, sha)
                if (prepared.isFile && prepared.length() > 0L) {
                    zip.putNextEntry(ZipEntry("prepared/text.json.gz")); prepared.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            check(tmp.renameTo(target)) { "Не удалось сохранить файл книги" }
            return true
        } finally { tmp.delete() }
    }
}
