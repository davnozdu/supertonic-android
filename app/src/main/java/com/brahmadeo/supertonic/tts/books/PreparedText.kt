package com.brahmadeo.supertonic.tts.books

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Готовый текст книги: ударения, ё и пунктуация, посчитанные заранее (при подготовке книги) и сохранённые рядом
 * с книгой. Ключ — отпечаток предложения (все буквы, без ограничения в 48), значение — само предложение в
 * подготовленном виде. Пока книга подготовлена полностью, при чтении нейросеть для текста не нужна: он берётся
 * отсюда. Текст, которого в файле нет или который не совпал по буквам, идёт обычным путём (LLM или словари).
 * Файл лежит отдельно от book.json: он большой, а SQLite не любит строки в мегабайты. */
object PreparedText {
    const val ALGORITHM = BookFingerprint.ALGORITHM
    const val FILE = "book-prepared"
    private const val MAX_BYTES = 64L * 1024 * 1024
    /** Предложение заканчивается на . ! ? … — так же, как их режет [BookFingerprint]. */
    private val separators = Regex("(?<=[.!?…])[\\s\\p{Z}\\u0085\\u001c-\\u001f]+")

    private val stores = HashMap<String, Map<Long, String>?>()
    private val lock = Any()

    /** Предложение и то, что шло за ним (пробелы, перевод строки) — при сборке текста всё возвращается на место. */
    data class Piece(val text: String, val separator: String)

    fun pieces(text: String): List<Piece> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Piece>()
        var start = 0
        for (m in separators.findAll(text)) {
            out += Piece(text.substring(start, m.range.first), text.substring(m.range.first, m.range.last + 1))
            start = m.range.last + 1
        }
        if (start < text.length) out += Piece(text.substring(start), "")
        else if (out.isNotEmpty()) out[out.lastIndex] = out.last().copy(separator = out.last().separator)
        return out
    }

    /** Отпечаток предложения по всем его буквам: короткие реплики («—») тоже получают свой ключ. */
    fun key(sentence: String): Long = BookFingerprint.hash(BookFingerprint.letters(sentence))

    fun file(context: Context, contentSha: String) = File(File(context.filesDir, FILE), "$contentSha.json.gz")

    private fun store(context: Context, contentSha: String): Map<Long, String>? = synchronized(lock) {
        if (stores.containsKey(contentSha)) return stores[contentSha]
        val f = file(context, contentSha)
        val loaded = runCatching {
            if (!f.isFile || f.length() <= 0L || f.length() > MAX_BYTES) return null
            val json = JSONObject(GZIPInputStream(f.inputStream().buffered()).bufferedReader().use { it.readText() })
            if (json.optString("algorithm") != ALGORITHM) return null
            val texts = json.optJSONObject("texts") ?: return null
            val map = HashMap<Long, String>(texts.length() * 2)
            for (key in texts.keys()) {
                val value = texts.optString(key)
                val hash = key.toULongOrNull(16L)?.toLong() ?: continue
                if (value.isNotBlank()) map[hash] = value
            }
            map
        }.getOrNull()
        stores[contentSha] = loaded
        loaded
    }

    /** Сколько предложений книги уже подготовлено (для экрана книги). */
    fun size(context: Context, contentSha: String): Int = store(context, contentSha)?.size ?: 0

    /** Готовый текст [text] из книги или null, если хотя бы одно предложение не найдено или разошлось по буквам. */
    fun lookup(context: Context, contentSha: String, text: String): String? {
        val store = store(context, contentSha) ?: return null
        val parts = pieces(text)
        if (parts.isEmpty()) return null
        val out = StringBuilder(text.length + text.length / 5)
        for (part in parts) {
            val got = store[key(part.text)] ?: return null
            // Слова и буквы обязаны совпасть: иначе это ответ для другого издания или испорченный файл.
            if (BookFingerprint.letters(got) != BookFingerprint.letters(part.text)) return null
            out.append(got).append(part.separator)
        }
        return out.toString()
    }

    /** Добавляет готовые предложения в набор книги. [pairs] — исходное и подготовленное предложение. */
    fun put(context: Context, contentSha: String, pairs: List<Pair<String, String>>): Int {
        if (pairs.isEmpty()) return 0
        val dir = File(context.filesDir, FILE).apply { mkdirs() }
        val f = file(context, contentSha)
        val texts = JSONObject()
        runCatching {
            if (f.isFile && f.length() in 1..MAX_BYTES) {
                val old = JSONObject(GZIPInputStream(f.inputStream().buffered()).bufferedReader().use { it.readText() })
                old.optJSONObject("texts")?.let { t -> for (k in t.keys()) texts.put(k, t.optString(k)) }
            }
        }
        var added = 0
        for ((source, prepared) in pairs) {
            if (source.isBlank() || prepared.isBlank()) continue
            if (BookFingerprint.letters(source) != BookFingerprint.letters(prepared)) continue
            val key = key(source).toULong().toString(16)
            if (texts.has(key)) continue
            texts.put(key, prepared); added++
        }
        if (added == 0) return 0
        val json = JSONObject().put("algorithm", ALGORITHM).put("version", 1).put("texts", texts)
        val tmp = File(dir, "${f.name}.tmp")
        GZIPOutputStream(tmp.outputStream().buffered()).bufferedWriter().use { it.write(json.toString()) }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        synchronized(lock) { stores.remove(contentSha) }
        return added
    }

    /** Забыть набор книги (удаление книги). */
    fun forget(context: Context, contentSha: String) {
        file(context, contentSha).delete()
        synchronized(lock) { stores.remove(contentSha) }
    }
}
