package com.brahmadeo.supertonic.tts.llm

/** Value of one Russian numeral word in any case, cardinal or ordinal: "пяти", "пятом",
 * "одну", "сорока", "тысячу" -> 5, 5, 1, 40, 1000. Lets the LLM inflect an already expanded
 * number ("сто одну книгу", "без пяти минут", "в тысяча девятьсот пятом году") while the
 * validator proves that every word still denotes the same component of the same number. */
object NumeralForms {
    private val forms: Map<String, Long> by lazy { build() }

    fun value(word: String): Long? = forms[word.lowercase().replace('ё', 'е')]

    private fun build(): Map<String, Long> {
        val m = HashMap<String, Long>()
        fun put(v: Long, vararg w: String) = w.forEach { m[it.replace('ё', 'е')] = v }
        // Ordinal adjective endings (masc/neut/fem, all cases, plural).
        val hard = listOf("ый", "ой", "ого", "ому", "ым", "ом", "ая", "ую", "ое", "ые", "ых", "ыми")
        fun ordinal(v: Long, stem: String, endings: List<String> = hard) = endings.forEach { put(v, stem + it) }
        put(0, "ноль", "ноля", "нолю", "нолём", "ноле", "нуль", "нуля", "нулю", "нулём", "нуле"); ordinal(0, "нулев")
        put(1, "один", "одного", "одному", "одним", "одном", "одна", "одной", "одну", "одною", "одно", "одни", "одних", "одними")
        ordinal(1, "перв")
        put(2, "два", "две", "двух", "двум", "двумя"); ordinal(2, "втор")
        put(3, "три", "трёх", "трём", "тремя"); put(3, "третий", "третьего", "третьему", "третьим", "третьем", "третья", "третьей", "третью", "третье", "третьи", "третьих", "третьими")
        put(4, "четыре", "четырёх", "четырём", "четырьмя"); ordinal(4, "четвёрт")
        // 5-20, 30: -ь / -и / -ью.
        val simple = listOf(5L to "пят", 6L to "шест", 7L to "сем", 9L to "девят", 10L to "десят",
            11L to "одиннадцат", 12L to "двенадцат", 13L to "тринадцат", 14L to "четырнадцат", 15L to "пятнадцат",
            16L to "шестнадцат", 17L to "семнадцат", 18L to "восемнадцат", 19L to "девятнадцат", 20L to "двадцат", 30L to "тридцат")
        for ((v, stem) in simple) put(v, stem + "ь", stem + "и", stem + "ью")
        put(8, "восемь", "восьми", "восемью", "восьмью")
        listOf(5L to "пят", 6L to "шест", 9L to "девят", 10L to "десят", 11L to "одиннадцат", 12L to "двенадцат",
            13L to "тринадцат", 14L to "четырнадцат", 15L to "пятнадцат", 16L to "шестнадцат", 17L to "семнадцат",
            18L to "восемнадцат", 19L to "девятнадцат", 20L to "двадцат", 30L to "тридцат").forEach { (v, s) -> ordinal(v, s) }
        ordinal(7, "седьм"); ordinal(8, "восьм")
        put(40, "сорок", "сорока"); ordinal(40, "сороков")
        for ((v, unit, gen) in listOf(Triple(50L, "пять", "пяти"), Triple(60L, "шесть", "шести"), Triple(70L, "семь", "семи"), Triple(80L, "восемь", "восьми"))) {
            put(v, unit + "десят", gen + "десяти", unit.dropLast(1) + "ьюдесятью", gen + "десятью")
            ordinal(v, gen + "десят")
        }
        put(80, "восемьюдесятью")
        put(90, "девяносто", "девяноста"); ordinal(90, "девяност")
        put(100, "сто", "ста"); ordinal(100, "сот")
        put(200, "двести", "двухсот", "двумстам", "двумястами", "двухстах"); ordinal(200, "двухсот")
        put(300, "триста", "трёхсот", "трёмстам", "тремстам", "тремястами", "трёхстах"); ordinal(300, "трёхсот")
        put(400, "четыреста", "четырёхсот", "четырёмстам", "четырьмястами", "четырёхстах"); ordinal(400, "четырёхсот")
        for ((v, gen, ins) in listOf(Triple(500L, "пяти", "пятью"), Triple(600L, "шести", "шестью"), Triple(700L, "семи", "семью"),
            Triple(800L, "восьми", "восемью"), Triple(900L, "девяти", "девятью"))) {
            val nom = mapOf(500L to "пятьсот", 600L to "шестьсот", 700L to "семьсот", 800L to "восемьсот", 900L to "девятьсот").getValue(v)
            put(v, nom, gen + "сот", gen + "стам", ins + "стами", gen + "стах"); ordinal(v, gen + "сот")
        }
        put(800, "восьмьюстами")
        put(1000, "тысяча", "тысячи", "тысяче", "тысячу", "тысячей", "тысячею", "тысячью", "тысяч", "тысячам", "тысячами", "тысячах")
        put(1000, "тысячный", "тысячного", "тысячному", "тысячным", "тысячном", "тысячная", "тысячной", "тысячную", "тысячное", "тысячные", "тысячных", "тысячными")
        for ((v, stem) in listOf(1_000_000L to "миллион", 1_000_000_000L to "миллиард")) {
            put(v, stem, stem + "а", stem + "у", stem + "ом", stem + "е", stem + "ы", stem + "ов", stem + "ам", stem + "ами", stem + "ах")
            ordinal(v, stem + "н", listOf("ый", "ого", "ому", "ым", "ом", "ая", "ой", "ую", "ое", "ые", "ых", "ыми"))
        }
        return m
    }
}
