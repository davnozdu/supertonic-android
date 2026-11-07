package com.brahmadeo.supertonic.tts.ambience

/**
 * Когда звук события разрешён: при «Накладывать звуки» не пропускается ничего — все размеченные по тексту звуки играют,
 * даже вплотную и поверх друг друга. Выключенный тумблер оставляет прежнее поведение: между началами звуков выдерживается
 * пауза [gap], а звук, который ещё звучит, ждёт (один за другим, без каши).
 */
object EventMix {

    /** Разрешено ли начать звук в [at] мс, если предыдущий начался в [lastAt] мс. */
    fun allowed(mix: Boolean, gap: Long, at: Long, lastAt: Long): Boolean =
        mix || gap <= 0L || at - lastAt >= gap

    /** Сколько звуков событий может звучать одновременно при наложении: защита ресурсов плеера, не квота на абзац. */
    const val MAX_SHOTS = 32

    /** Не занят ли канал: без наложения — пока звучит предыдущий; с наложением — когда звучат уже [MAX_SHOTS]. */
    fun blocked(mix: Boolean, busy: Boolean, sounding: Int = 0): Boolean = if (mix) sounding >= MAX_SHOTS else busy

    /** Уровень следующего звука, когда [sounding] уже звучат: −1,4 дБ за каждый (0,85ⁿ). */
    fun overlapGain(sounding: Int): Float {
        var g = 1f
        repeat(sounding.coerceIn(0, MAX_SHOTS)) { g *= 0.85f }
        return g
    }

    /** Наименьший разнос начал: два звука в один миг сливаются в кашу, поэтому при наложении они всё равно идут
     * чередой — друг за другом, а не одновременно. */
    const val STAGGER_MS = 220L

    /**
     * Времена звуков одного куска текста. Каждый звук привязан к своему слову ([times] — по словам, с вычетом
     * «звук раньше текста»); если два звука приходится на одно и то же слово (или «раньше текста» уводит начало в
     * минус у начала куска), они всё равно не начинаются одновременно: следующий сдвигается на [STAGGER_MS].
     * Так плотный абзац звучит чередой событий, а не одним слипшимся ударом.
     */
    fun scheduled(times: List<Long>, mix: Boolean, gap: Long, stagger: Long = STAGGER_MS): List<Long> {
        val out = ArrayList<Long>(times.size)
        var last = -1L
        for (t in times) {
            val at = maxOf(0L, t)
            val start = if (last < 0L) at else maxOf(at, 0L + (if (mix) last + stagger else last + maxOf(gap, 0L)))
            out += start
            last = start
        }
        return out
    }
}
