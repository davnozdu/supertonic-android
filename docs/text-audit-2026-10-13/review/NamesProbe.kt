package com.brahmadeo.supertonic.tts.llm

import com.brahmadeo.supertonic.tts.utils.*
import java.io.File
import java.util.zip.GZIPInputStream

fun main(args: Array<String>) {
    RussianNames.initForTests(File(args[0]).useLines { RussianNames.load(it) })
    val sacc = BinaryAccentDictionary.open(File(args[1]))!!
    val homographs = GZIPInputStream(File(args[2]).inputStream()).bufferedReader().useLines { l -> l.mapNotNull { line ->
        val t = line.indexOf('\t'); if (t <= 0) null else line.substring(0, t) to line.substring(t + 1).split(',').map { it.toInt() } }.toMap() }
    val silero = File(args[3]).readLines().associate { it.split('\t').let { x -> x[0] to x[1] } }
    fun plusAcute(v: String) = Regex("\\+([аеёиоуыэюяАЕЁИОУЫЭЮЯ])").replace(v) { it.groupValues[1] + "́" }
    fun ord(v: String?) = v?.let { StressCheck.ordinal(plusAcute(it)) }
    fun ru(w: String): Int? = sacc.lookup(w.lowercase().toByteArray())?.let { ord(it) }
    var rows = 0; val tally = mutableMapOf<String, Int>()
    for (line in File(args[4]).readLines()) {
        val forms = line.split('|'); val ref = StressCheck.ordinal(forms[0])!!
        for (f in forms) {
            val w = f.replace("́", "")
            val s0 = silero.getValue(w)
            val sileroSaysYo = 'ё' in s0.lowercase()
            val sil = plusAcute(s0)
            val composite = RussianNames.overlay(null, sil)   // Silero + names dictionary, as RussianStressVote.silero
            val r = ru(w)
            val name = RussianNames.ordinal(w)
            fun outcome(llm: Int): String {
                val p = StressCheck.plan(listOf("Вчера пришёл ${StressCheck.withStress(w, llm)}."), listOf("Вчера пришёл $composite."),
                    { k -> if (k == w.lowercase()) r else null }, { homographs[it] })
                val res = StressCheck.resolve(p, null) { d -> if (d.first.first().isUpperCase()) RussianNames.ordinal(d.first.replace("́","")) else null }.single()
                val got = StressCheck.ordinal(res.removePrefix("Вчера пришёл ").removeSuffix("."))
                return (if (p.disputes.isNotEmpty()) "арбитр/" else if (p.agreed > 0) "согласие/" else if (p.single > 0) "один/" else "LLM/") + (if (got == ref) "ок" else "ОШ")
            }
            val vowels = w.count { it in "аеёиоуыэюяАЕЁИОУЫЭЮЯ" }
            val wrong = if (ref == 1) 2 else 1
            val a = outcome(ref); val b = if (vowels > 1) outcome(wrong) else "-"
            tally["LLM верна: $a"] = (tally["LLM верна: $a"] ?: 0) + 1
            tally["LLM ошиблась: $b"] = (tally["LLM ошиблась: $b"] ?: 0) + 1
            rows++
            println(listOf(StressCheck.withStress(w, ref), "имена=" + (RussianNames.form(w) ?: "-"), "RUAccent=" + (r ?: "-"),
                "homo=" + (homographs[w.lowercase()] ?: "-"), "Silero=" + sil + (if (sileroSaysYo) "(ё)" else ""),
                "Silero" + (if (ord(s0) == ref) "✓" else "✗"), "LLMверна→$a", "LLMошиблась→$b").joinToString("  "))
        }
    }
    println("rows=$rows"); tally.toSortedMap().forEach { (k, v) -> println("$k = $v") }
}
