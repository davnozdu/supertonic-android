package com.brahmadeo.supertonic.tts.books.prepare

import java.io.File

/** Text cleanup for analysis and the book's structure: stories of a collection, chapters of a novel
 * (same rules as tools/characters/book_characters.py). Fingerprints always use the original text. */
object BookStructure {
    /** Collection: the ten main characters have this share of their mentions in their «home» section. */
    const val COLLECTION_CONCENTRATION = 0.75

    private val INVISIBLE = Regex("[\\u00ad\\u200b-\\u200f\\u2060\\ufeff\\u202a-\\u202e]")
    private const val LATIN = "aeopcyxAEOPCTXKMHB"
    private const val CYRILLIC = "аеорсухАЕОРСТХКМНВ"
    private val MIXED_WORD = Regex("[A-Za-zА-ЯЁа-яё]+")
    private val HAS_CYRILLIC = Regex("[А-ЯЁа-яё]")
    private val HAS_LATIN = Regex("[A-Za-z]")
    private val DASH_START = Regex("^\\s*(?:--?|[‐‑‒–—―−])\\s*")
    private val DASH_INSIDE = Regex("(?<=\\S)\\s+(?:--?|[‐‒–—―−])\\s+|(?<=[,.!?…»\"])(?:--?|[‒–—―−])\\s+")
    private val FOOTNOTE = Regex("\\[\\d{1,3}]|\\{\\d{1,3}}|[¹²³⁰⁴-⁹]+")
    private val DECOR = Regex("[*•§~_#|¤◆◇■□●○★☆►▪︎❖✦✧]+")
    private val OPEN_QUOTE = Regex("\"(?=[\\p{L}\\p{N}_])")
    private val CLOSE_QUOTE = Regex("(?<=\\S)\"")
    private val SPACE_BEFORE = Regex("\\s+([.,!?…;:»)])")
    private val SPACES = Regex("\\s+")
    private val QUOTES = mapOf('„' to '«', '“' to '»', '”' to '»', '‟' to '«', '‹' to '«', '›' to '»', '"' to '«')

    /** Mechanical cleanup before analysis: any dash («-», «--», «–», «―») → «—», quotes → «», «...» → «…»; no
     * invisible characters, footnote marks («[1]», «¹»), asterisks or decoration; Latin look-alike letters inside
     * Russian words («Pогожин» from a scan) → Cyrillic. */
    fun normalize(source: String): String {
        var text = INVISIBLE.replace(source, "")
        text = FOOTNOTE.replace(text, "")
        text = DECOR.replace(text, " ")
        text = MIXED_WORD.replace(text) { m ->
            val w = m.value
            if (HAS_CYRILLIC.containsMatchIn(w) && HAS_LATIN.containsMatchIn(w))
                w.map { c -> LATIN.indexOf(c).let { if (it >= 0) CYRILLIC[it] else c } }.joinToString("") else w
        }
        text = text.replace("...", "…")
        if (text.length > 2 && DASH_START.containsMatchIn(text)) text = DASH_START.replaceFirst(text, "— ")
        text = DASH_INSIDE.replace(text, " — ")
        text = OPEN_QUOTE.replace(text, "«")
        text = CLOSE_QUOTE.replace(text, "»")
        text = text.map { QUOTES[it] ?: it }.joinToString("")
        text = SPACE_BEFORE.replace(text, "$1")
        return SPACES.replace(text, " ").trim()
    }

    /** The same book with text cleaned for analysis; footnotes become empty so paragraph numbers still match. */
    fun normalized(book: EpubBook): EpubBook = book.copy(paragraphs = book.paragraphs.mapIndexed { i, p ->
        p.copy(text = if (i in book.notes) "" else normalize(book.analysis[i] ?: p.text))
    })

    /** A section of one or two paragraphs («ЧАСТЬ ПЕРВАЯ.», a title page) joins the next one. */
    fun mergeTiny(book: EpubBook, smallest: Int = 3): EpubBook {
        val size = book.paragraphs.groupingBy { it.section }.eachCount()
        val target = mutableMapOf<Int, Int>()
        val carry = mutableListOf<Int>()
        for (s in size.keys.sorted()) {
            carry += s
            if (size.getValue(s) >= smallest) { carry.forEach { target[it] = s }; carry.clear() }
        }
        for (x in carry) target[x] = target.values.maxOrNull() ?: x
        val used = target.values.toSortedSet().toList()
        val renumber = used.withIndex().associate { it.value to it.index }
        return book.copy(sections = used.mapIndexed { i, old -> book.sections[old].copy(id = "s${i + 1}") },
            paragraphs = book.paragraphs.map { it.copy(section = renumber.getValue(target.getValue(it.section))) })
    }

    private val HEADING = Regex("^(?:(?:глава|часть|книга|chapter|part)\\s+(?:[0-9]{1,3}|[ivxlcdm]{1,7}|[а-яё-]{3,20})|" +
        "[ivxlcdm]{1,7}|[0-9]{1,3})\\.?$", RegexOption.IGNORE_CASE)

    /** Chapters of a novel: every level of the table of contents; with no such levels — headings in the text
     * («Глава 5», «XII.», «12.» as a paragraph); otherwise the book as it is. A boundary is always the start of a
     * heading or a contents entry: the book is never cut mechanically by size. */
    fun chapters(file: File, check: () -> Unit = {}): EpubBook {
        val book = BookInput.read(file, EpubBook.ALL_LEVELS, check)
        if (book.sections.size >= 3) return mergeTiny(book)
        val marks = book.paragraphs.indices.filter { book.paragraphs[it].text.length <= 40 && HEADING.matches(book.paragraphs[it].text.trim()) }
        val gaps = marks.zipWithNext { a, b -> b - a }.sorted()
        if (marks.size < 3 || gaps[gaps.size / 2] < 10) return book
        val starts = marks.toSet()
        val sections = mutableListOf<EpubBook.Section>()
        val paragraphs = mutableListOf<EpubBook.Paragraph>()
        var current = -1
        var previous: Int? = null
        book.paragraphs.forEachIndexed { i, p ->
            if (i in starts || p.section != previous || current < 0) {
                sections += EpubBook.Section("s${sections.size + 1}", if (i in starts) p.text else book.sections[p.section].title)
                current = sections.size - 1
            }
            previous = p.section
            paragraphs += EpubBook.Paragraph(current, p.text)
        }
        return mergeTiny(book.copy(sections = sections, paragraphs = paragraphs))
    }

    /** Stories of a collection. A contents entry is split into the entries of the next level only when each of them
     * has its own characters (like stories); the same heroes going through them mean chapters of one story. */
    fun stories(file: File, book: EpubBook, check: () -> Unit, names: (EpubBook) -> List<NameCandidates.Candidate>): EpubBook {
        var current = book
        for (depth in 2..5) {
            check()
            val deeper = BookInput.read(file, depth, check)
            if (deeper.paragraphs.size != current.paragraphs.size || deeper.sections.size <= current.sections.size) break
            val named = names(normalized(deeper)).filter { it.kind == "name" }
            val children = mutableMapOf<Int, MutableSet<Int>>()
            current.paragraphs.zip(deeper.paragraphs).forEach { (unit, child) -> children.getOrPut(unit.section) { mutableSetOf() } += child.section }
            val split = children.mapValues { (_, kids) ->
                val heroes = named.map { c -> kids.sumOf { c.sections[it] ?: 0 } to c }.sortedByDescending { it.first }.take(10).filter { it.first >= 5 }
                val total = heroes.sumOf { it.first }
                val home = heroes.sumOf { (_, c) -> kids.maxOf { c.sections[it] ?: 0 } }
                kids.size > 1 && heroes.size >= 2 && home >= COLLECTION_CONCENTRATION * total
            }
            if (split.values.none { it }) break
            val keys = mutableMapOf<Pair<Boolean, Int>, Int>()
            val sections = mutableListOf<EpubBook.Section>()
            val paragraphs = mutableListOf<EpubBook.Paragraph>()
            current.paragraphs.zip(deeper.paragraphs).forEach { (unit, child) ->
                val own = split.getValue(unit.section)
                val key = own to if (own) child.section else unit.section
                val index = keys.getOrPut(key) {
                    sections += EpubBook.Section("s${sections.size + 1}",
                        if (own) deeper.sections[child.section].title else current.sections[unit.section].title)
                    sections.size - 1
                }
                paragraphs += EpubBook.Paragraph(index, unit.text)
            }
            current = current.copy(sections = sections, paragraphs = paragraphs)
        }
        return mergeTiny(current) // a part title («Люди») joins the first story
    }

    /** Collection: every story has its own characters. Measured by the share of mentions of the ten main named
     * characters in their «home» section: almost all in a collection, 0.2–0.55 in novels. Repeated section titles
     * («Тед», «Оливия», «Тед»…) are chapters of a novel told by different narrators. */
    fun collection(book: EpubBook, candidates: Collection<NameCandidates.Candidate>): Boolean {
        if (book.sections.size < 3) return false
        val titles = book.sections.groupingBy { it.title }.eachCount()
        if (titles.values.filter { it > 1 }.sum() > book.sections.size / 3.0) return false
        val named = candidates.filter { it.kind == "name" }.sortedByDescending { it.count }.take(10)
        val total = named.sumOf { it.count }
        if (total == 0) return false
        return named.sumOf { c -> c.sections.values.maxOrNull() ?: 0 }.toDouble() / total >= COLLECTION_CONCENTRATION
    }
}
