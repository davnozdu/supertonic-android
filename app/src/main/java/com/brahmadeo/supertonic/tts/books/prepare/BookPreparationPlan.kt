package com.brahmadeo.supertonic.tts.books.prepare

import com.brahmadeo.supertonic.tts.books.BookFingerprint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Compact plan: the full book text is released before the first cloud request. */
data class BookPreparationPlan(val title: String, val author: String, val fileSha: String, val contentSha: String,
    val sections: List<EpubBook.Section>, val collection: Boolean, val requests: List<Request>, val fingerprints: Map<String, List<String>>) {
    data class Request(val name: String, val title: String, val sections: List<String>, val candidates: List<NameCandidates.Candidate>, val prompt: String) {
        val prefix get() = candidates.firstOrNull()?.id?.trimEnd { it.isDigit() } ?: "c"
    }
    fun verifyPrompt(request: Request, answer: JSONObject) = CastPrompts.verify(
        if (collection) "рассказе «${request.title}»" else "книге «$title»", request.prefix, answer, request.candidates)

    fun export(results: List<CastCheck.Result>): String {
        require(results.size == requests.size)
        val sectionCast = requests.flatMapIndexed { index, request -> request.sections.map { it to index } }.toMap()
        return JSONObject().put("format", "mytts-book").put("version", 1)
            .put("book", JSONObject().put("title", title).put("author", author).put("file_sha256", fileSha).put("content_sha256", contentSha))
            .put("scope", if (collection) "section" else "book").put("voice_model", "")
            .put("sections", JSONArray(sections.map { JSONObject().put("id", it.id).put("title", it.title).put("cast", sectionCast[it.id] ?: JSONObject.NULL) }))
            .put("casts", JSONArray(results.mapIndexed { i, result -> result.export(requests[i].candidates, requests[i].sections) }))
            .put("fingerprint", JSONObject().put("algorithm", BookFingerprint.ALGORITHM).put("min_letters", BookFingerprint.MIN_LETTERS))
            .put("fingerprints", JSONObject(fingerprints)).toString()
    }

    companion object {
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun create(file: File, names: Set<String>, stage: (String) -> Unit = {}, check: () -> Unit = {}): BookPreparationPlan {
            stage("Чтение EPUB")
            val book = EpubBook.read(file, check)
            stage("Поиск кандидатов")
            val extraction = NameCandidates(names, check).extract(book)
            val requests = extraction.candidates.filter { it.count >= 2 || it.speaker > 0 }.groupBy { it.scope }.toSortedMap().map { (scope, group) ->
                val ranked = group.sortedWith(compareByDescending<NameCandidates.Candidate> { it.count + 3 * it.speaker }.thenBy { it.key }).take(200)
                val prefix = if (extraction.collection) "s${scope + 1}c" else "c"
                ranked.forEachIndexed { i, c -> c.id = "$prefix${i + 1}" }
                val name = if (extraction.collection) book.sections[scope].id else "book"
                val title = if (extraction.collection) book.sections[scope].title else book.title
                val what = if (extraction.collection) "рассказ «$title» из книги «${book.title}»" else "книгу «$title»"
                Request(name, title, if (extraction.collection) listOf(name) else book.sections.map { it.id }, ranked, CastPrompts.main(what, prefix, ranked))
            }
            require(requests.isNotEmpty()) { "В книге не найдено достаточно кандидатов" }
            val digest = MessageDigest.getInstance("SHA-256")
            val index = linkedMapOf<Long, String?>()
            book.paragraphs.forEachIndexed { i, p ->
                check()
                if (i > 0) digest.update('\n'.code.toByte())
                digest.update(BookFingerprint.letters(p.text).toByteArray(Charsets.UTF_8))
                val sid = book.sections[p.section].id
                for (fp in BookFingerprint.sentences(p.text)) index[fp] = if (index.containsKey(fp) && index[fp] != sid) null else sid
            }
            val contentSha = digest.digest().joinToString("") { "%02x".format(it) }
            val fileDigest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) { check(); val n = input.read(buf); if (n < 0) break; fileDigest.update(buf, 0, n) }
            }
            val fileSha = fileDigest.digest().joinToString("") { "%02x".format(it) }
            val prints = index.entries.filter { it.value != null }.groupBy({ it.value!! }, { BookFingerprint.hex(it.key) }).mapValues { it.value.sorted() }
            return BookPreparationPlan(book.title, book.author, fileSha, contentSha, book.sections, extraction.collection, requests, prints)
        }
    }
}
