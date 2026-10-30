package com.brahmadeo.supertonic.tts.books.prepare

import android.content.Context
import com.brahmadeo.supertonic.tts.books.BookFingerprint
import com.brahmadeo.supertonic.tts.books.PreparedText
import com.brahmadeo.supertonic.tts.llm.LlmProviders
import java.io.File

/** Готовит текст книги заранее: те же ударения, ё и пунктуация, что при чтении, но посчитанные один раз при
 * подготовке и записанные в файл книги ([PreparedText]). Пакеты по [CHARS] знаков — как на чтении, поэтому
 * результат совпадает. Каждый пакет кэшируется по своему тексту: повторная подготовка ничего не пересчитывает.
 * Сбой облака не портит книгу: что успело посчитаться, то и попадёт в файл, остальное при чтении пойдёт обычным
 * путём — облаком, если оно есть, или словарями. */
object PreparedMarking {
    private const val CHARS = 2400
    data class Result(val paragraphs: Int, val sentences: Int, val failed: Int,
                      /** Отпечаток предложения → готовый текст: идёт в book.json. */
                      val store: Map<Long, String>)

    internal fun mark(ctx: Context, plan: BookPreparationPlan, source: BookPreparation.Source,
             folder: File, cancel: LlmProviders.CloudCancellation, startedAt: Long,
             progress: (stage: String, done: Int, all: Int) -> Unit): Result {
        val paragraphs = plan.paragraphs.filter { it.text.isNotBlank() }
        if (paragraphs.isEmpty()) return Result(0, 0, 0, emptyMap())
        val batches = batch(paragraphs.map { it.text })
        val store = HashMap<Long, String>(paragraphs.size * 4)
        var done = 0
        var sentences = 0
        var failed = 0
        for (batch in batches) {
            cancel.check()
            progress("Готовый текст", done, paragraphs.size)
            val pairs = try {
                val prepared = prepare(batch, folder, source, cancel)
                batch.zip(prepared).filter { (a, b) ->
                    // Только дословные ответы: ударения и ё, ни одного изменённого слова.
                    BookFingerprint.letters(a) == BookFingerprint.letters(b)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += batch.size
                emptyList()
            }
            for ((source, prepared) in pairs) for ((hash, value) in PreparedText.pairs(source, prepared)) {
                if (store.put(hash, value) == null) sentences++
            }
            done += batch.size
        }
        progress("Готовый текст", paragraphs.size, paragraphs.size)
        return Result(paragraphs.size, sentences, failed, store)
    }

    /** Пакеты не длиннее [CHARS]: одно и то же деление и при повторной подготовке, и при дозаписи. */
    private fun batch(texts: List<String>): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var size = 0
        for (t in texts) {
            if (current.isNotEmpty() && size + t.length > CHARS) { out += current; current = mutableListOf(); size = 0 }
            current += t; size += t.length
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /** Кэш ответа по самому тексту пакета: та же книга и модель — ни одного лишнего запроса. */
    private fun prepare(batch: List<String>, folder: File, source: BookPreparation.Source,
                        cancel: LlmProviders.CloudCancellation): List<String> {
        val key = BookPreparationPlan.hash(("prep-text\n${source.label}\n" + batch.joinToString("\u0000")).toByteArray()).take(32)
        val cache = File(folder, "text.$key.json")
        if (cache.isFile) {
            val lines = runCatching { cache.readLines() }.getOrDefault(emptyList())
            if (lines.size == batch.size) return lines
        }
        val prepared = LlmProviders.cloud(source.config, batch, source.gemini, provider = source.provider, slow = true)
        cancel.check()
        runCatching {
            val tmp = File(folder, "${cache.name}.tmp")
            tmp.writeText(prepared.joinToString("\n") { it.replace("\n", " ") })
            if (!tmp.renameTo(cache)) { tmp.copyTo(cache, overwrite = true); tmp.delete() }
        }
        return prepared
    }
}
