package com.brahmadeo.supertonic.tts.books.prepare

/**
 * Шаги и транспорт, названные в тексте прямо: поверхность («по гравию», «по асфальту», «по половицам») и движение
 * (шёл, вышла, побежал) вместе дают событие шагов; «завёл мотор», «хлопнул дверцей» — событие машины. Модель такие
 * бытовые действия иногда пропускает, а слушатель сразу слышит, что герой вышел — и ничего не прозвучало.
 *
 * Правило узкое: нужны и слово поверхности, и глагол движения в том же абзаце. Без поверхности шаги ставит модель.
 */
object MovementSounds {

    /** Поверхность (начало слова, нижний регистр) → вид события. */
    private val SURFACES: List<Pair<String, String>> = listOf(
        "грави" to "footsteps_gravel", "щеб" to "footsteps_gravel", "гальк" to "footsteps_gravel",
        "асфальт" to "footsteps_stone", "тротуар" to "footsteps_stone", "бетон" to "footsteps_stone",
        "камн" to "footsteps_stone", "плитк" to "footsteps_stone", "мостов" to "footsteps_stone",
        "снег" to "footsteps_snow", "сугроб" to "footsteps_snow", "наст" to "snow_crunch",
        "половиц" to "footsteps_wood", "паркет" to "footsteps_wood",
        "линолеум" to "footsteps_wood", "доск" to "footsteps_wood",
        "крыльц" to "footsteps_wood", "террас" to "footsteps_wood", "сцен" to "footsteps_wood",
        "лестниц" to "footsteps_stairs", "ступен" to "footsteps_stairs",
        "трав" to "leaves_step", "листь" to "leaves_step", "листв" to "leaves_step",
        "гряз" to "footsteps_mud", "луж" to "footsteps_water",
        // «наст» — это и наст снега, и «настежь», «настаивал»; такие слова отсекаются в [EXCEPTIONS].
        "наст" to "snow_crunch"
    )

    /** Точные слова-поверхности: «по полу», «на полу» — но не «получил», «полустанок». */
    private val EXACT: Map<String, String> = mapOf(
        "пол" to "footsteps_wood", "полу" to "footsteps_wood",
        "полом" to "footsteps_wood", "полы" to "footsteps_wood"
    )

    /** Слова, которые начинаются как поверхность, но ею не являются: иначе «получил письмо» даёт шаги по полу. */
    private val EXCEPTIONS = listOf(
        "получ", "полус", "полуо", "полум", "полук", "полюс",
        "наста", "насто", "насте", "насты", "насти",
        "досказ", "доскоч", "травл", "трави", "травн"
    )

    /** Глаголы движения (начало слова): без них поверхность — просто упоминание. */
    private val MOTION = listOf(
        "шёл", "шел", "шла", "шли", "идёт", "идет", "идут", "пошёл", "пошел", "пошла", "пошли",
        "вышел", "вышла", "вышли", "вошёл", "вошел", "вошла", "вошли", "подошёл", "подошел", "подошла",
        "поднима", "спуска", "шагнул", "шагнула", "ступил", "ступила", "кралс", "кралась", "шарка",
        "бежал", "бежала", "побежал", "побежала", "мчалс", "брел", "брела", "прошёл", "прошел", "прошла",
        "прошёлся", "прошелся", "гулял", "гуляла", "вернулс", "вернулась", "направил", "направилась", "стуча"
    )

    /** Женские каблуки: слово «каблук» или «шпилька» в том же абзаце. */
    private fun heels(words: List<String>) = words.any { it.startsWith("каблук") || it.startsWith("шпильк") }

    /** Номер слова, где назван шаг по поверхности, или null. */
    fun surfaceIndex(words: List<String>): Int? {
        val lower = words.map { VoiceSounds.clean(it) }
        if (lower.none { w -> MOTION.any { m -> w.startsWith(m) } }) return null
        for ((i, w) in lower.withIndex()) {
            if (w.isEmpty()) continue
            if (EXCEPTIONS.any { w.startsWith(it) }) continue
            if (w !in EXACT && SURFACES.none { (p, _) -> w.startsWith(p) }) continue
            if (VoiceSounds.negated(words, i)) continue
            return i
        }
        return null
    }

    /** Вид шагов по названной поверхности ([at] — номер слова поверхности). */
    fun surfaceKind(words: List<String>, at: Int): String {
        val w = VoiceSounds.clean(words.getOrNull(at) ?: "")
        val kind = EXACT[w] ?: SURFACES.firstOrNull { (p, _) -> w.startsWith(p) }?.second ?: "footsteps_wood"
        if (!heels(words.map { VoiceSounds.clean(it) })) return kind
        // Женщина в каблуках: по камню и гравию — цоканье снаружи, по половицам и паркету — стук в доме.
        return when (kind) {
            "footsteps_stone", "footsteps_gravel", "footsteps_stairs", "heel_walk_stone" -> "heel_walk_stone"
            "footsteps_wood" -> "heel_walk_wood"
            else -> kind
        }
    }

    /** Завод двигателя и дверца машины: «завёл мотор», «мотор завёлся», «хлопнул дверцей». */
    fun carIndex(words: List<String>): Pair<Int, String>? {
        val lower = words.map { VoiceSounds.clean(it) }
        val text = lower.joinToString(" ")
        val move = listOf("завёл", "завел", "завела", "завелся", "завёлся", "завелась", "зародил")
        if (lower.any { w -> move.any { w.startsWith(it) } } && (("мотор" in text) || ("двигател" in text) || ("машин" in text)))
            return lower.indexOfFirst { w -> move.any { w.startsWith(it) } }.let { it to "car_start" }
        if (("дверц" in text || ("двер" in text && "машин" in text)) && lower.any { it.startsWith("хлопнул") || it.startsWith("захлопнул") })
            return lower.indexOfFirst { it.startsWith("хлопнул") || it.startsWith("захлопнул") }.let { it to "car_door_slam" }
        return null
    }
}
