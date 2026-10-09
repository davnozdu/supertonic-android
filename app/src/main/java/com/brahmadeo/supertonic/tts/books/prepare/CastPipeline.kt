package com.brahmadeo.supertonic.tts.books.prepare

import org.json.JSONObject

/** The LLM part of preparation, as in tools/characters/book_characters.py: three independent main answers
 * (majority vote), «who is this» passages for titles and bare surnames (without thinking: a choice from a list),
 * then verification of every merge. Incomplete verification degrades (unverified merges → «прочие») instead of
 * failing the book. Pure JVM: [ask] performs one cached request with retries and returns a valid answer or throws.
 * [ask]: (cache name, prompt, kind, thinking, accept) → answer. */
class CastPipeline(
    private val plan: BookPreparationPlan,
    private val thinking: Boolean,
    private val ask: (String, String, CastCheck.Kind, Boolean, (JSONObject) -> Boolean) -> JSONObject,
    private val stage: (String, Int, Int) -> Unit = { _, _, _ -> },
    private val check: () -> Unit = {},
    private val votes: Int = VOTES,
) {
    /** Problems that did not stop the book (shown in the result message). */
    val notes = mutableListOf<String>()

    fun run(): List<List<CastCheck.Cast>> = plan.requests.mapIndexed { i, r ->
        check()
        val label = "${i + 1}/${plan.requests.size} · ${r.title}"
        // Several independent answers: a merge stays only if the majority made it.
        val answers = mutableListOf<JSONObject>()
        var failure: Exception? = null
        for (n in 1..votes) {
            check()
            stage("LLM $label · ответ $n из $votes", i, plan.requests.size)
            try { answers += ask("${r.name}.run$n", r.prompt, CastCheck.Kind.MAIN, thinking) { true } }
            catch (e: java.util.concurrent.CancellationException) { throw e }
            catch (e: Exception) { if (isCancellation(e)) throw e; failure = e }
        }
        if (answers.size < votes / 2 + 1) throw failure ?: IllegalStateException("LLM не вернула ответы; повторите подготовку")
        val answer = CastCheck.combine(answers, r)
        val whos = linkedMapOf<Pair<String, String>, List<String>>()
        val targets = CastCheck.labelTargets(r, answer)
        for ((t, target) in targets.withIndex()) {
            val (_, ref) = target
            val portions = CastCheck.labelRequests(r, answer, ref, plan.collection)
            val all = mutableListOf<String>()
            for (portion in portions) {
                check()
                stage("Проверка обращений $label · ${t + 1}/${targets.size}", i, plan.requests.size)
                val votesOf = { a: JSONObject -> CastCheck.labelVotes(a, portion.hi - portion.lo) }
                val result = try { votesOf(ask(r.name + portion.suffix, portion.prompt, CastCheck.Kind.LABELS, LABEL_THINK) { votesOf(it) != null }) }
                    catch (e: java.util.concurrent.CancellationException) { throw e }
                    catch (e: Exception) { if (isCancellation(e)) throw e; null }
                if (result == null) { all.clear(); break }
                all += result
            }
            // No answer for the passages: the strict rules apply to this title or surname.
            if (all.size == r.byId.getValue(ref).contexts.size && all.isNotEmpty()) whos[target] = all
        }
        val prompt = CastCheck.verifyPrompt(r, answer, plan.collection)
        val checked = if (prompt == null) null else {
            stage("Проверка склеек $label", i, plan.requests.size)
            val name = "${r.name}.verify"
            try { ask(name, prompt, CastCheck.Kind.VERIFY, thinking) { CastCheck.verificationComplete(it, answer, r) } }
            catch (e: java.util.concurrent.CancellationException) { throw e }
            catch (e: Exception) {
                if (isCancellation(e)) throw e
                // Still incomplete after retries: take the answer as it is; unverified merges are not accepted.
                notes += "${r.title}: проверка склеек неполна"
                try { ask("$name.partial", prompt, CastCheck.Kind.VERIFY, thinking) { true } }
                catch (e2: java.util.concurrent.CancellationException) { throw e2 }
                catch (e2: Exception) { if (isCancellation(e2)) throw e2; null }
            }
        }
        val verdicts = CastCheck.verdicts(checked, answer, r)
        stage("Готово $label", i + 1, plan.requests.size)
        CastCheck.casts(r, answer, verdicts, whos, plan.collection)
    }

    private fun isCancellation(e: Throwable): Boolean = e is java.util.concurrent.CancellationException ||
        e.javaClass.name.endsWith("CancellationException") || e.cause?.let(::isCancellation) == true

    companion object {
        const val VOTES = 3
        /** «Who is this in the passage» is a choice from a list: faster without thinking and never loops. */
        const val LABEL_THINK = false
    }
}
