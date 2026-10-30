package com.brahmadeo.supertonic.tts.books.smart

import android.content.Context
import com.brahmadeo.supertonic.tts.books.prepare.BookPreparation
import com.brahmadeo.supertonic.tts.llm.LlmProviders
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** «Ранее в книге», «Эта глава», «Чем закончится» by the cloud LLM chosen in the settings, without thinking.
 * Section summaries are made once, one request at a time, and saved; the final answer is kept per place. */
object SmartRecap {
    enum class Kind(val title: String) {
        BEFORE("Ранее в книге"), PREVIOUS("Предыдущая глава"), CHAPTER("Эта глава"), WHO("Кто есть кто"), ENDING("Чем закончится"),
        ASK("Вопрос о книге")
    }
    /** [partial]: the retelling as far as it has come (streamed), read aloud sentence by sentence before it ends. */
    data class State(val running: Boolean = false, val kind: Kind? = null, val stage: String = "", val done: Int = 0, val total: Int = 0,
                     val result: String? = null, val error: String? = null, val book: String = "", val partial: String = "")

    /** «Вопросы о книге» (off by default; a switch in «Умная книга»). */
    const val PREF_QUESTIONS = "smart_book_questions"
    fun questions(context: Context) = context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getBoolean(PREF_QUESTIONS, false)
    /** «Озвучивать ответ»: the answer to a question read aloud (on), or only shown. */
    const val PREF_ANSWER_SPEAK = "smart_book_answer_speak"
    fun answerSpoken(context: Context) = context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE).getBoolean(PREF_ANSWER_SPEAK, true)

    /** Places this many paragraphs apart share an answer: a step further in the book asks nothing again. */
    private const val BUCKET = 10

    /** The cache key of an answer: a chapter's own answer does not depend on the place inside the next one. */
    private fun answerId(key: String, kind: Kind, text: SmartText?, paragraph: Int) = "$key/$kind/" + when (kind) {
        Kind.ENDING -> "end"
        Kind.PREVIOUS -> "s${text?.sectionOf(paragraph) ?: paragraph}"
        else -> "p${paragraph / BUCKET}"
    }

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var cancellation: LlmProviders.CloudCancellation? = null
    /** Answers already made: (key, kind, paragraph) → text. Only in memory. */
    private val answers = LinkedHashMap<String, String>()

    /** Returns the answer at once when it was already made for this place. */
    @Synchronized fun start(context: Context, kind: Kind, position: SmartMatcher.Position): String? {
        if (job?.isActive == true) return null
        val ctx = context.applicationContext
        val cancel = LlmProviders.CloudCancellation().also { cancellation = it }
        val id = answerId(position.key, kind, if (kind == Kind.PREVIOUS) SmartLibrary.text(ctx, position.key) else null, position.paragraph)
        synchronized(answers) { answers[id] }?.let { mutable.value = State(kind = kind, result = it, book = mutable.value.book); return it }
        mutable.value = State(true, kind, "Подготовка")
        job = scope.launch {
            try {
                val text = requireNotNull(SmartLibrary.text(ctx, position.key)) { "Текст книги не найден. Обновите папку или подготовьте книгу снова." }
                mutable.value = mutable.value.copy(book = text.title)
                val source = BookPreparation.source(ctx).withThinking(false)
                val result = Work(ctx, position.key, text, source, cancel).run(kind, position.paragraph)
                synchronized(answers) { answers[id] = result; while (answers.size > 20) answers.remove(answers.keys.first()) }
                // The streamed text stays: what was not read aloud yet is read from it.
                mutable.value = State(kind = kind, result = result, book = text.title, partial = mutable.value.partial)
            } catch (_: CancellationException) {
                mutable.value = State(kind = kind, error = "Остановлено.")
            } catch (t: Exception) {
                val stopped = runCatching { cancel.check() }.isFailure
                mutable.value = State(kind = kind, error = if (stopped) "Остановлено." else safeError(t))
            } finally { synchronized(this@SmartRecap) { job = null; cancellation = null } }
        }
        return null
    }

    /** «Спросить о книге»: [question] about the place being read. [position]: the recognised book (its text before the place
     * and the summaries already made), or null — then only [page] (the page the reader sent) is the context. [last]: the
     * sentence heard last. One request, streamed and read aloud; never cached (the same question may mean another thing). */
    @Synchronized fun ask(context: Context, question: String, position: SmartMatcher.Position?, page: String, last: String): Boolean {
        if (job?.isActive == true || question.isBlank()) return false
        val ctx = context.applicationContext
        val cancel = LlmProviders.CloudCancellation().also { cancellation = it }
        mutable.value = State(true, Kind.ASK, "Подготовка")
        job = scope.launch {
            try {
                val text = position?.let { SmartLibrary.text(ctx, it.key) }
                val source = BookPreparation.source(ctx).withThinking(false)
                mutable.value = mutable.value.copy(book = text?.title.orEmpty())
                val work = Work(ctx, position?.key.orEmpty(), text ?: SmartText("", "", emptyList()), source, cancel)
                val result = work.answer(question, if (text != null) position?.paragraph else null, page, last)
                mutable.value = State(kind = Kind.ASK, result = result, book = text?.title.orEmpty(), partial = mutable.value.partial)
            } catch (_: CancellationException) {
                mutable.value = State(kind = Kind.ASK, error = "Остановлено.")
            } catch (t: Exception) {
                val stopped = runCatching { cancel.check() }.isFailure
                mutable.value = State(kind = Kind.ASK, error = if (stopped) "Остановлено." else safeError(t))
            } finally { synchronized(this@SmartRecap) { job = null; cancellation = null } }
        }
        return true
    }

    @Synchronized fun cancel() { cancellation?.cancel(); job?.cancel() }
    fun clear() { if (!mutable.value.running) mutable.value = State() }

    private class Work(val ctx: Context, val key: String, val text: SmartText, val source: BookPreparation.Source,
                      val cancel: LlmProviders.CloudCancellation) {
        val summaries = HashMap(text.summaries)

        fun run(kind: Kind, paragraph: Int): String {
            val section = text.sectionOf(paragraph)
            val sec = text.sections[section]
            return when (kind) {
                Kind.BEFORE -> {
                    val listing = condensed(0 until section)
                    stage("Пересказ", 0, 0)
                    speech(SmartPrompts.before(listing, sec.title, SmartPrompts.tail(text.sectionText(section, paragraph))), 3000)
                }
                Kind.WHO -> {
                    val listing = condensed(0 until section)
                    // A prepared book knows its characters' names: the model does not misspell or invent them.
                    val names = runCatching {
                        com.brahmadeo.supertonic.tts.books.BookLibrary.contentShas(ctx).entries.firstOrNull { it.value == key }?.key
                            ?.let { com.brahmadeo.supertonic.tts.books.BookLibrary.get(ctx, it) }
                            ?.casts?.flatMap { c -> c.characters.map { it.name } }?.distinct()?.take(40)
                    }.getOrNull().orEmpty()
                    stage("Пересказ", 0, 0)
                    speech(SmartPrompts.who(listing, sec.title, SmartPrompts.tail(text.sectionText(section, paragraph)), names), 3000)
                }
                Kind.PREVIOUS -> {
                    // The whole chapter before this one, from its own text (more detail than its stored summary).
                    val previous = (section - 1 downTo 0).firstOrNull { text.sectionText(it).isNotBlank() }
                        ?: throw IllegalStateException("Текст книги: это первая глава, предыдущей нет")
                    stage("Пересказ", 0, 0)
                    speech(SmartPrompts.previous(text.sections[previous].title, SmartPrompts.tail(text.sectionText(previous))), 2500)
                }
                Kind.CHAPTER -> {
                    val current = text.sectionText(section, paragraph)
                    val previous = if (current.length < SmartPrompts.SHORT_CHAPTER && section > 0) summary(section - 1) else null
                    stage("Пересказ", 0, 0)
                    speech(SmartPrompts.chapter(sec.title, SmartPrompts.tail(current), previous), 2500)
                }
                Kind.ASK -> throw IllegalStateException("Вопрос задаётся через ask()")
                Kind.ENDING -> {
                    val last = text.sections.indices.last { text.sectionText(it).isNotBlank() }
                    val listing = condensed(0 until last)
                    stage("Пересказ концовки", 0, 0)
                    speech(SmartPrompts.ending(listing, text.sections[last].title, SmartPrompts.tail(text.sectionText(last))), 3000)
                }
            }
        }

        /** A question: the summaries already made (none made now — one request, an answer in seconds), the chapter read
         * so far (or the reader's page), the last sentence and the cast. */
        fun answer(question: String, paragraph: Int?, page: String, last: String): String {
            val section = paragraph?.let { text.sectionOf(it) }
            val listing = if (section == null) "" else (0 until section).mapNotNull { i ->
                summaries[i]?.let { "«${text.sections[i].title}»: $it" }
            }.joinToString("\n").takeLast(SmartPrompts.SUMMARIES_CHARS / 3)
            val current = if (section != null) SmartPrompts.tail(text.sectionText(section, paragraph), 20_000) else page.takeLast(12_000)
            val names = if (key.isBlank()) emptyList() else runCatching {
                com.brahmadeo.supertonic.tts.books.BookLibrary.contentShas(ctx).entries.firstOrNull { it.value == key }?.key
                    ?.let { com.brahmadeo.supertonic.tts.books.BookLibrary.get(ctx, it) }
                    ?.casts?.flatMap { c -> c.characters.map { it.name } }?.distinct()?.take(40)
            }.getOrNull().orEmpty()
            stage("Ответ", 0, 0)
            return speech(SmartPrompts.ask(listing, section?.let { text.sections[it].title }.orEmpty(), current, last.take(1000), names, question), 1500)
        }

        /** Summaries of [range], made where missing; joined in groups when too long for one request. */
        fun condensed(range: IntRange): String {
            val wanted = range.filter { text.sectionText(it).isNotBlank() }
            make(wanted)
            val made = wanted.map { summaries.getValue(it) }
            var lines = wanted.map { text.sections[it].title }.zip(made).map { (t, s) -> "«$t»: $s" }
            while (lines.sumOf { it.length + 1 } > SmartPrompts.SUMMARIES_CHARS && lines.size > 1) {
                val groups = mutableListOf<MutableList<String>>(mutableListOf())
                for (line in lines) {
                    if (groups.last().isNotEmpty() && groups.last().sumOf { it.length + 1 } + line.length > SmartPrompts.SUMMARIES_CHARS / 2) groups += mutableListOf<String>()
                    groups.last() += line
                }
                lines = groups.mapIndexed { i, g ->
                    stage("Объединение кратких содержаний", i, groups.size)
                    ask(SmartPrompts.join(g.joinToString("\n")), 3000)
                }
            }
            return lines.joinToString("\n")
        }

        /** Makes the missing summaries of [sections], several sections per request, and saves them. */
        fun make(sections: List<Int>) {
            summaries += SmartPrompts.summarize(text, sections, summaries, { prompt, tokens, multi -> request(source, prompt, tokens, cancel, multi) },
                { done, total -> stage("Краткое содержание разделов", done, total) },
                { section, summary -> SmartLibrary.saveSummary(ctx, key, section, summary) })
        }

        fun summary(section: Int): String { make(listOf(section)); return summaries[section].orEmpty() }

        fun stage(name: String, done: Int, total: Int) { mutable.value = mutable.value.copy(stage = name, done = done, total = total) }

        fun ask(prompt: String, tokens: Int): String = request(source, prompt, tokens, cancel)

        /** The retelling itself: streamed as plain text, so it is read aloud while it is written. If the stream fails
         * before anything came, the ordinary request is made instead. */
        fun speech(prompt: String, tokens: Int): String {
            val got = StringBuilder()
            try {
                val text = LlmProviders.cloudStream(source.config, SmartPrompts.plain(prompt), PromptOverrides.get("smart-plain"), source.provider,
                    tokens, 3 * 60 * 1000L, cancel) { piece ->
                    got.append(piece)
                    mutable.value = mutable.value.copy(stage = "Пересказ", partial = got.toString())
                }
                SmartPrompts.answer(text)?.let { return it }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                cancel.check()
                if (got.isNotBlank()) throw e // part of it was already read aloud: another answer would not continue it
            }
            mutable.value = mutable.value.copy(partial = "")
            return ask(prompt, tokens)
        }
    }

    /** One request without thinking: up to three attempts on network or server errors. */
    /** [multi]: a request of [SmartPrompts.several]; its raw JSON answer is returned. */
    internal fun request(source: BookPreparation.Source, prompt: String, tokens: Int, cancel: LlmProviders.CloudCancellation, multi: Boolean = false): String {
        var delay = 3000L
        var last: Exception? = null
        for (attempt in 1..3) {
            cancel.check()
            try {
                val own = PromptOverrides.get("smart")
                val raw = LlmProviders.cloudRequest(source.config, prompt, if (multi) SmartPrompts.severalOf(own) else own,
                    if (multi) SmartPrompts.severalSchema else SmartPrompts.schema, source.gemini,
                    tokens = tokens, deadlineMs = 3 * 60 * 1000L, cancellation = cancel, provider = source.provider, temperature = 0.3)
                if (multi) { if (raw.contains("\"summaries\"")) return raw } else SmartPrompts.answer(raw)?.let { return it }
                last = IllegalStateException("LLM вернула ответ не в формате JSON")
            } catch (e: CancellationException) { throw e
            } catch (e: LlmProviders.CloudOutputLimitException) { last = e
            } catch (e: Exception) {
                cancel.check()
                val message = e.message.orEmpty()
                val temporary = e is java.io.IOException || message.startsWith("LLM превышен лимит запроса") || message.startsWith("API HTTP 429") || Regex("^API HTTP 5\\d\\d").containsMatchIn(message)
                if (!temporary || attempt == 3) throw e
                last = e
            }
            val until = System.currentTimeMillis() + delay
            while (System.currentTimeMillis() < until) { cancel.check(); Thread.sleep(300) }
            delay *= 2
        }
        throw last ?: IllegalStateException("LLM не вернула ответ")
    }

    /** Provider messages may hold URLs or credentials: only known ones are shown. */
    private fun safeError(t: Exception): String {
        val message = t.message.orEmpty()
        return "Ошибка: " + when {
            message.startsWith("API HTTP") -> message
            t is java.net.SocketTimeoutException -> "облако не ответило вовремя, повторите"
            message.startsWith("Настройте") || message.startsWith("Текст книги") || message.startsWith("LLM вернула") || message.startsWith("LLM не вернула") -> message
            t is LlmProviders.CloudOutputLimitException -> "ответ LLM не уместился в лимит, повторите"
            else -> "не удалось получить пересказ. Проверьте сеть и настройки облачной LLM."
        }
    }
}
