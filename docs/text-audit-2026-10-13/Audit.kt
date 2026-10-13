package com.brahmadeo.supertonic.tts.llm

import com.brahmadeo.supertonic.tts.utils.*
import com.brahmadeo.supertonic.tts.kokoro.KokoroG2p
import java.io.File

fun main(args: Array<String>) {
    val names = File(args[0]).useLines { RussianNames.load(it) }
    RussianNames.initForTests(names)
    fun show(id: String, value: Any?) = println("$id: $value")
    fun vote(llm: String, silero: String, dictionary: Int?, variants: List<Int>? = null): StressCheck.Plan =
        StressCheck.plan(listOf(llm), listOf(silero), { dictionary }, { variants })
    val rare = vote("Пришёл Залуме́ров.", "Пришёл Залу́меров.", null)
    show("A01 rare surname", "disputes=${rare.disputes.size}, result=${StressCheck.resolve(rare, null).single()}")
    val triple = vote("Выходи́те.", "Вы́ходите.", null, listOf(1, 2, 3))
    show("A02 three variants", "disputes=${triple.disputes.size}, result=${StressCheck.resolve(triple, null).single()}")
    show("A03 explicit name", RussianNames.restore(null, "Позвали Фе́дора."))
    show("A04 explicit ordinary yo", YoRestore.apply("че́рный", emptySet()) { "чёрный" })
    show("A05 capitals", RussianNames.restore(null, "Позвали ФЕДОРА и федора."))
    NameStress.clear()
    NameStress.learn("Я говорил с Залу́меровым.")
    show("A06 morphology", NameStress.fill("Залумеров пришёл к Залумерову и Залумеровым."))
    NameStress.clear()
    NameStress.learn("Залуме́ров вошёл. Залуме́ров ответил.")
    show("A07 sentence start", NameStress.hint(listOf("Залумеров")))
    NameStress.learn("Пришёл Залу́меров.")
    show("A08 correction of marked name", NameStress.fill("Пришёл Залуме́ров."))
    RussianNames.forget()
    RussianNames.restore(null, "Я позвал Семена.")
    show("A09 seeds after name", RussianNames.restore(null, "Семена взошли весной."))
    show("A10 lost sentence boundaries", PreparedTextValidator.validate("Да. Нет. Это было не раз.", "Да Нет Это бы́ло не раз."))
    show("A11 introduced yo", PreparedTextValidator.validate("Он был рассерженный.", "Он был рассержённый."))
    val yo = vote("Он рассержённый.", "Он рассе́рженный.", 2)
    show("A12 yo bypass", "disputes=${yo.disputes.size}, result=${StressCheck.resolve(yo, null).single()}")
    show("A13 low coverage", PreparedTextValidator.validate("Настасья Филипповна увидела Рогожина.", "Наста́сья Филипповна увидела Рогожина."))
    show("A14 Kokoro proper name", KokoroG2p.marked("Он приехал в То́го."))
    show("A15 misplaced name mark", run { NameStress.clear(); NameStress.learn("Пришёл Залум́еров."); NameStress.fill("Залумеров") })
    show("A16 offline yo disabled", RussianYoPolicy.apply("Позвали Федора.", RussianNames.restore(null, "Позвали Федора."), false))
    val repeated = StressCheck.plan(listOf("У за́мка нет за́мка."), listOf("У за́мка нет замка́."), { 1 }, { listOf(1,2) },
        { _, w -> w in StressCheck.explicitWords("У за́мка нет замка.") })
    show("A17 explicit occurrence scope", "disputes=${repeated.disputes.size}, result=${StressCheck.resolve(repeated,null).single()}")
    val forms = listOf("иванов", "иванова", "иванову", "рогожин", "рогожина", "мышкин", "мышкина", "салтыков-щедрин", "жюль", "пьер", "дюран", "дюпонт", "федор", "федора")
    show("dictionary size", names.size)
    show("dictionary hyphenated", names.keys.count { '-' in it })
    forms.forEach { show("dictionary $it", names[it]) }
    var count = 0
    val tests = listOf("llm.StressCheckTest", "llm.NameStressTest", "llm.PreparedTextValidatorTest", "llm.PreparedTextRepairTest", "utils.NamesAndYoTest", "utils.RussianYoPolicyTest", "utils.BookTextSpacingTest")
    tests.forEach { name ->
        val cls = Class.forName("com.brahmadeo.supertonic.tts.$name")
        cls.declaredMethods.filter { it.getAnnotation(org.junit.Test::class.java) != null }.forEach { m ->
            try { m.invoke(cls.getDeclaredConstructor().newInstance()); count++ }
            catch (e: java.lang.reflect.InvocationTargetException) { throw AssertionError("$name.${m.name}", e.cause) }
        }
    }
    show("existing JVM tests passed", count)
}
