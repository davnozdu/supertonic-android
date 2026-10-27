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
    enum class Kind(val title: String) { BEFORE("Ранее в книге"), CHAPTER("Эта глава"), ENDING("Чем закончится") }
    data class State(val running: Boolean = false, val kind: Kind? = null, val stage: String = "", val done: Int = 0, val total: Int = 0,
                     val result: String? = null, val error: String? = null, val book: String = "")

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
        val id = "${position.key}/$kind/${position.paragraph}"
        synchronized(answers) { answers[id] }?.let { mutable.value = State(kind = kind, result = it, book = mutable.value.book); return it }
        mutable.value = State(true, kind, "Подготовка")
        job = scope.launch {
            try {
                val text = requireNotNull(SmartLibrary.text(ctx, position.key)) { "Текст книги не найден. Обновите папку или подготовьте книгу снова." }
                mutable.value = mutable.value.copy(book = text.title)
                val source = BookPreparation.source(ctx).withThinking(false)
                val result = Work(ctx, position.key, text, source, cancel).run(kind, position.paragraph)
                synchronized(answers) { answers[id] = result; while (answers.size > 20) answers.remove(answers.keys.first()) }
                mutable.value = State(kind = kind, result = result, book = text.title)
            } catch (_: CancellationException) {
                mutable.value = State(kind = kind, error = "Остановлено.")
            } catch (t: Exception) {
                val stopped = runCatching { cancel.check() }.isFailure
                mutable.value = State(kind = kind, error = if (stopped) "Остановлено." else safeError(t))
            } finally { synchronized(this@SmartRecap) { job = null; cancellation = null } }
        }
        return null
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
                    ask(SmartPrompts.before(listing, sec.title, SmartPrompts.tail(text.sectionText(section, paragraph))), 3000)
                }
                Kind.CHAPTER -> {
                    val current = text.sectionText(section, paragraph)
                    val previous = if (current.length < SmartPrompts.SHORT_CHAPTER && section > 0) summary(section - 1, 1, 1) else null
                    stage("Пересказ", 0, 0)
                    ask(SmartPrompts.chapter(sec.title, SmartPrompts.tail(current), previous), 2500)
                }
                Kind.ENDING -> {
                    val last = text.sections.indices.last { text.sectionText(it).isNotBlank() }
                    val listing = condensed(0 until last)
                    stage("Пересказ концовки", 0, 0)
                    ask(SmartPrompts.ending(listing, text.sections[last].title, SmartPrompts.tail(text.sectionText(last))), 3000)
                }
            }
        }

        /** Summaries of [range], made where missing; joined in groups when too long for one request. */
        fun condensed(range: IntRange): String {
            val wanted = range.filter { text.sectionText(it).isNotBlank() }
            val made = wanted.mapIndexed { i, s -> summary(s, i + 1, wanted.size) }
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

        fun summary(section: Int, index: Int, total: Int): String {
            summaries[section]?.let { return it }
            val title = text.sections[section].title
            val whole = text.sectionText(section)
            if (whole.length < SmartPrompts.TINY_SECTION) return whole.replace('\n', ' ').also { summaries[section] = it }
            val parts = SmartPrompts.parts(whole)
            val made = parts.mapIndexed { i, part ->
                stage("Краткое содержание разделов", index - 1, total)
                ask(SmartPrompts.section(title, i, parts.size, part), 3000)
            }.joinToString(" ")
            SmartLibrary.saveSummary(ctx, key, section, made)
            summaries[section] = made
            return made
        }

        fun stage(name: String, done: Int, total: Int) { mutable.value = mutable.value.copy(stage = name, done = done, total = total) }

        fun ask(prompt: String, tokens: Int): String = request(source, prompt, tokens, cancel)
    }

    /** One request without thinking: up to three attempts on network or server errors. */
    internal fun request(source: BookPreparation.Source, prompt: String, tokens: Int, cancel: LlmProviders.CloudCancellation): String {
        var delay = 3000L
        var last: Exception? = null
        for (attempt in 1..3) {
            cancel.check()
            try {
                val raw = LlmProviders.cloudRequest(source.config, prompt, SmartPrompts.SYSTEM, SmartPrompts.schema, source.gemini,
                    tokens = tokens, deadlineMs = 3 * 60 * 1000L, cancellation = cancel, provider = source.provider, temperature = 0.3)
                SmartPrompts.answer(raw)?.let { return it }
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
