package com.brahmadeo.supertonic.tts.books.prepare

import com.brahmadeo.supertonic.tts.books.BookFingerprint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Compact plan: the full book text is released before the first cloud request. A novel is split into chapters
 * (titles are decided chapter by chapter), a collection into stories (each with its own characters). */
data class BookPreparationPlan(val title: String, val author: String, val fileSha: String, val contentSha: String,
    val sections: List<EpubBook.Section>, val collection: Boolean, val requests: List<Request>, val fingerprints: Map<String, List<String>>) {
    data class Request(val name: String, val title: String, val sections: List<String>, val candidates: List<NameCandidates.Candidate>, val prompt: String) {
        val byId: Map<String, NameCandidates.Candidate> by lazy { candidates.associateBy { it.id } }
        val prefix get() = candidates.firstOrNull()?.id?.trimEnd { it.isDigit() } ?: "c"
    }

    /** [casts]: the casts of every request, in order. */
    fun export(casts: List<List<CastCheck.Cast>>): String {
        require(casts.size == requests.size)
        val all = casts.flatMapIndexed { i, list -> list.map { it to requests[i] } }
        val sectionCast = all.flatMapIndexed { index, (cast, _) -> cast.sections.map { it to index } }.toMap()
        return JSONObject().put("format", "mytts-book").put("version", 1)
            .put("book", JSONObject().put("title", title).put("author", author).put("file_sha256", fileSha).put("content_sha256", contentSha))
            .put("scope", if (collection) "section" else "book").put("voice_model", "")
            .put("sections", JSONArray(sections.map { JSONObject().put("id", it.id).put("title", it.title).put("cast", sectionCast[it.id] ?: JSONObject.NULL) }))
            .put("casts", JSONArray(all.map { (cast, request) -> CastCheck.export(cast, request) }))
            .put("fingerprint", JSONObject().put("algorithm", BookFingerprint.ALGORITHM).put("min_letters", BookFingerprint.MIN_LETTERS))
            .put("fingerprints", JSONObject(fingerprints)).toString()
    }

    companion object {
        /** Passages checked by the LLM for a title or a bare surname across the book. */
        const val LABEL_SAMPLES = 16
        /** A title in a novel: passages from each chapter where it occurs (a decision per chapter). */
        const val LABEL_PER_CHAPTER = 3
        const val LABEL_PER_BUSY_CHAPTER = 5
        const val MAX_CANDIDATES = 200
        const val MIN_COUNT = 2

        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun <T> spread(items: List<T>, limit: Int): List<T> {
            if (items.size <= limit) return items
            val step = items.size.toDouble() / limit
            return (0 until limit).map { items[(it * step + step / 2).toInt()] }
        }

        /** A title («генерал») and a bare surname may mean different people: passages for them. */
        private fun needsContexts(c: NameCandidates.Candidate) = c.kind == "title" || (c.kind == "name" && ' ' !in c.key && (c.roles["Surn"] ?: 0) > 0)

        /** Passages for «who is this»: the previous paragraph of the same section and the mention, word in [[…]]. */
        private fun contexts(book: EpubBook, c: NameCandidates.Candidate, perChapter: Boolean) {
            val chosen = if (perChapter) c.spots.groupBy { book.paragraphs[it.first].section }.values
                .flatMap { spread(it, if (it.size >= 10) LABEL_PER_BUSY_CHAPTER else LABEL_PER_CHAPTER) }
                else spread(c.spots, LABEL_SAMPLES)
            val out = mutableListOf<String>(); val where = mutableListOf<String>()
            for ((paragraph, start, end) in chosen) {
                val p = book.paragraphs[paragraph]
                val text = p.text
                val left = maxOf(0, start - 320)
                var before = (if (left > 0) "…" else "") + text.substring(left, start)
                if (start < 200 && paragraph > 0 && book.paragraphs[paragraph - 1].section == p.section) {
                    val previous = book.paragraphs[paragraph - 1].text
                    before = (if (previous.length > 220) "…" else "") + previous.takeLast(220) + "\n" + before
                }
                val right = minOf(text.length, end + 160)
                out += before + "[[" + text.substring(start, end) + "]]" + text.substring(end, right) + if (right < text.length) "…" else ""
                where += book.sections[p.section].id
            }
            c.contexts = out; c.contextSections = where
        }

        fun create(file: File, names: Set<String>, morph: BookMorph = BookMorph.EMPTY, stage: (String) -> Unit = {}, check: () -> Unit = {}): BookPreparationPlan {
            stage("Чтение книги")
            fun extractor() = NameCandidates(names, morph, check)
            var book = BookInput.read(file, check = check)
            stage("Поиск кандидатов")
            var text = BookStructure.normalized(book)
            var found = extractor().run(text, false)
            val collection = BookStructure.collection(book, found)
            if (collection) {
                stage("Деление на рассказы")
                book = BookStructure.stories(file, book, check) { extractor().run(it, false) }
                text = BookStructure.normalized(book)
                found = extractor().run(text, true)
            } else {
                // A novel by chapters: a title («генерал») is decided in each chapter where it is unambiguous.
                stage("Деление на главы")
                val chaptered = BookStructure.chapters(file, check)
                if (chaptered.sections.size > book.sections.size) {
                    book = chaptered; text = BookStructure.normalized(book)
                    found = extractor().run(text, false)
                }
            }
            val groups = found.filter { c ->
                !(c.descriptor && c.speaker < 2 && c.titles.isEmpty()) && // a description met once is not a character
                    (c.count >= MIN_COUNT || c.speaker > 0 || (c.kind == "name" && ((c.roles["Name"] ?: 0) > 0 || (c.roles["Patr"] ?: 0) > 0)))
            }.groupBy { it.scope }.toSortedMap()
            val requests = groups.mapNotNull { (scope, group) ->
                check()
                val ranked = group.sortedWith(compareByDescending<NameCandidates.Candidate> { it.count + 3 * it.speaker }.thenBy { it.key }).take(MAX_CANDIDATES)
                if (ranked.isEmpty()) return@mapNotNull null
                val prefix = if (collection) "s${scope + 1}c" else "c"
                ranked.forEachIndexed { i, c -> c.id = "$prefix${i + 1}" }
                for (c in ranked) {
                    c.sectionCounts = c.sections.entries.associate { book.sections[it.key].id to it.value }
                    c.speakerCounts = c.speakerSections.entries.associate { book.sections[it.key].id to it.value }
                    if (needsContexts(c)) contexts(text, c, !collection && c.kind == "title")
                }
                val section = book.sections[scope]
                val name = if (collection) section.id else "book"
                val title = if (collection) section.title else book.title
                val what = if (collection) "рассказ «$title» из книги «${book.title}»" else "книгу «$title»"
                Request(name, title, if (collection) listOf(name) else book.sections.map { it.id }, ranked, CastPrompts.main(what, prefix, ranked))
            }
            require(requests.isNotEmpty()) { "В книге не найдено достаточно кандидатов" }
            // Sentence fingerprints → section, from the original text. A repeat in different sections is dropped.
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
            return BookPreparationPlan(book.title, book.author, fileSha, contentSha, book.sections, collection, requests, prints)
        }
    }
}
