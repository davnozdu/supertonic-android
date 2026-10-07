package com.brahmadeo.supertonic.tts.utils

/** Offline book normalization. Runs before stress restoration; preserves author punctuation.
 * Ambiguous case is left nominative instead of inventing a different number or noun. */
object RussianBookNormalizer {
    private val patterns = java.util.concurrent.ConcurrentHashMap<String,Regex>()
    private fun rx(pattern: String, option: RegexOption? = null): Regex = patterns.getOrPut(pattern + option) { if(option==null) Regex(pattern) else Regex(pattern,option) }
    private val numbers = RussianNumberNormalizer()
    enum class Case { NOM, GEN, DAT, ACC, INS, PRE }
    private val forms = listOf(
        "ноль|нуля|нулю|ноль|нулём|нуле", "один|одного|одному|один|одним|одном",
        "одна|одной|одной|одну|одной|одной", "одно|одного|одному|одно|одним|одном",
        "два|двух|двум|два|двумя|двух", "две|двух|двум|две|двумя|двух",
        "три|трёх|трём|три|тремя|трёх", "четыре|четырёх|четырём|четыре|четырьмя|четырёх",
        "пять|пяти|пяти|пять|пятью|пяти", "шесть|шести|шести|шесть|шестью|шести",
        "семь|семи|семи|семь|семью|семи", "восемь|восьми|восьми|восемь|восемью|восьми",
        "девять|девяти|девяти|девять|девятью|девяти", "десять|десяти|десяти|десять|десятью|десяти",
        "одиннадцать|одиннадцати|одиннадцати|одиннадцать|одиннадцатью|одиннадцати",
        "двенадцать|двенадцати|двенадцати|двенадцать|двенадцатью|двенадцати",
        "тринадцать|тринадцати|тринадцати|тринадцать|тринадцатью|тринадцати",
        "четырнадцать|четырнадцати|четырнадцати|четырнадцать|четырнадцатью|четырнадцати",
        "пятнадцать|пятнадцати|пятнадцати|пятнадцать|пятнадцатью|пятнадцати",
        "шестнадцать|шестнадцати|шестнадцати|шестнадцать|шестнадцатью|шестнадцати",
        "семнадцать|семнадцати|семнадцати|семнадцать|семнадцатью|семнадцати",
        "восемнадцать|восемнадцати|восемнадцати|восемнадцать|восемнадцатью|восемнадцати",
        "девятнадцать|девятнадцати|девятнадцати|девятнадцать|девятнадцатью|девятнадцати",
        "двадцать|двадцати|двадцати|двадцать|двадцатью|двадцати",
        "тридцать|тридцати|тридцати|тридцать|тридцатью|тридцати",
        "сорок|сорока|сорока|сорок|сорока|сорока",
        "пятьдесят|пятидесяти|пятидесяти|пятьдесят|пятьюдесятью|пятидесяти",
        "шестьдесят|шестидесяти|шестидесяти|шестьдесят|шестьюдесятью|шестидесяти",
        "семьдесят|семидесяти|семидесяти|семьдесят|семьюдесятью|семидесяти",
        "восемьдесят|восьмидесяти|восьмидесяти|восемьдесят|восемьюдесятью|восьмидесяти",
        "девяносто|девяноста|девяноста|девяносто|девяноста|девяноста",
        "сто|ста|ста|сто|ста|ста", "двести|двухсот|двумстам|двести|двумястами|двухстах",
        "триста|трёхсот|трёмстам|триста|тремястами|трёхстах",
        "четыреста|четырёхсот|четырёмстам|четыреста|четырьмястами|четырёхстах",
        "пятьсот|пятисот|пятистам|пятьсот|пятьюстами|пятистах",
        "шестьсот|шестисот|шестистам|шестьсот|шестьюстами|шестистах",
        "семьсот|семисот|семистам|семьсот|семьюстами|семистах",
        "восемьсот|восьмисот|восьмистам|восемьсот|восемьюстами|восьмистах",
        "девятьсот|девятисот|девятистам|девятьсот|девятьюстами|девятистах",
        "тысяча|тысячи|тысяче|тысячу|тысячей|тысяче", "тысячи|тысяч|тысячам|тысячи|тысячами|тысячах",
        "тысяч|тысяч|тысячам|тысяч|тысячами|тысячах", "миллион|миллиона|миллиону|миллион|миллионом|миллионе",
        "миллиона|миллионов|миллионам|миллиона|миллионами|миллионах", "миллионов|миллионов|миллионам|миллионов|миллионами|миллионах",
        "миллиард|миллиарда|миллиарду|миллиард|миллиардом|миллиарде", "миллиарда|миллиардов|миллиардам|миллиарда|миллиардами|миллиардах",
        "миллиардов|миллиардов|миллиардам|миллиардов|миллиардами|миллиардах"
    ).map { it.split('|') }.associateBy { it[0] }
    private val ordinalRoots = mapOf(1 to "перв",2 to "втор",3 to "треть",4 to "четвёрт",5 to "пят",6 to "шест",7 to "седьм",8 to "восьм",9 to "девят",10 to "десят",11 to "одиннадцат",12 to "двенадцат",13 to "тринадцат",14 to "четырнадцат",15 to "пятнадцат",16 to "шестнадцат",17 to "семнадцат",18 to "восемнадцат",19 to "девятнадцат",20 to "двадцат",30 to "тридцат",40 to "сороков",50 to "пятидесят",60 to "шестидесят",70 to "семидесят",80 to "восьмидесят",90 to "девяност",100 to "сот",200 to "двухсот",300 to "трёхсот",400 to "четырёхсот",500 to "пятисот",600 to "шестисот",700 to "семисот",800 to "восьмисот",900 to "девятисот",1000 to "тысячн",2000 to "двухтысячн",3000 to "трёхтысячн",4000 to "четырёхтысячн",5000 to "пятитысячн",6000 to "шеститысячн",7000 to "семитысячн",8000 to "восьмитысячн",9000 to "девятитысячн")
    // Stressed ending: второй, шестой, седьмой, восьмой, сороковой (not -ый).
    private val stressedOrdinals = setOf("втор","шест","седьм","восьм","сороков")
    fun cardinal(n: Long, case: Case = Case.NOM, feminine: Boolean = false): String {
        var base = numbers.spellInteger(n)
        if (feminine && base.endsWith("один")) base = base.removeSuffix("один")+"одна"
        if (feminine && base.endsWith("два")) base = base.removeSuffix("два")+"две"
        return base.split(' ').joinToString(" ") { forms[it]?.get(case.ordinal) ?: it }
    }
    fun ordinal(n: Int, ending: String): String {
        fun last(root: String) = when {
            root == "треть" -> "трет" + thirdEnding(ending)
            ending == "ый" && root in stressedOrdinals -> root + "ой"
            else -> root + ending
        }
        ordinalRoots[n]?.let { return last(it) }
        val tail = if (n % 100 in 1..19) n%100 else if (n%10 != 0) n%10 else if (n%100 != 0) n%100 else n%1000
        val root = ordinalRoots[tail] ?: return cardinal(n.toLong())
        // Only the last word is ordinal; a leading thousand is read without "одна":
        // "тысяча девятьсот девяносто первого", not "одна тысяча ...".
        val head = cardinal((n-tail).toLong()).takeIf { n != tail }.orEmpty().let { if (it.startsWith("одна тысяча")) it.removePrefix("одна ") else it }
        return (if (head.isEmpty()) "" else "$head ") + last(root)
    }
    private fun thirdEnding(ending: String) = mapOf("ый" to "ий","ая" to "ья","ое" to "ье","ого" to "ьего","ому" to "ьему","ом" to "ьем",
        "ым" to "ьим","ую" to "ью","ой" to "ьей","ые" to "ьи","ых" to "ьих","ыми" to "ьими")[ending] ?: ending
    private fun plural(n: Long, one: String, few: String, many: String): String = when {
        kotlin.math.abs(n)%100 in 11..14 -> many
        kotlin.math.abs(n)%10==1L -> one
        kotlin.math.abs(n)%10 in 2..4 -> few
        else -> many
    }
    private fun unitForm(n: Long, unit: List<String>, case: Case): String {
        if(case==Case.NOM || case==Case.ACC) return plural(n,unit[0],unit[1],unit[2])
        val first=unit[0].substringBefore(' ');val tail=unit[0].substringAfter(' ',"").let { if(it.isEmpty()) "" else " $it" }
        if(first=="евро") return first
        val single=kotlin.math.abs(n)%10==1L && kotlin.math.abs(n)%100!=11L
        val soft=first.endsWith("ль")
        val stem=if(soft) first.dropLast(1) else first
        val form=when(case) {
            Case.GEN -> if(single) stem+if(soft) "я" else "а" else unit[2].substringBefore(' ')
            Case.DAT -> stem+if(single) { if(soft) "ю" else "у" } else if(soft) "ям" else "ам"
            Case.INS -> stem+if(single) { if(soft) "ём" else "ом" } else if(soft) "ями" else "ами"
            Case.PRE -> stem+if(single) "е" else if(soft) "ях" else "ах"
            else -> first
        }
        return form+tail
    }
    private fun inferredCase(before: String, after: String = ""): Case {
        val last = rx("[а-яё]+").findAll(before.lowercase()).lastOrNull()?.value
        val next=rx("[а-яё]+").find(after.lowercase())?.value.orEmpty()
        if(last in setOf("в","на") && (next.endsWith("ах") || next.endsWith("ях"))) return Case.PRE
        if(last in setOf("с","со") && next in setOf("лет","часов","минут","секунд","дней")) return Case.GEN
        return when(last) {
            "без","до","из","от","около","после","для","более","менее","нет","лишился","достиг","достигли","требует","хватает" -> Case.GEN
            "к","по" -> Case.DAT
            "между","перед","над","с","со" -> Case.INS
            "о","об","при" -> Case.PRE
            else -> Case.NOM
        }
    }
    // Latin abbreviations are expanded before foreign-language routing.
    fun abbreviations(text: String): String {
        var t=text
        for ((key,value) in mapOf("Wi-Fi" to "вай-фай","hi-fi" to "хай-фай","WIFI" to "вайфай","SIM" to "сим","PIN" to "пин","GIF" to "гиф","MIDI" to "миди"))
            t=rx("(?<![\\p{L}])${Regex.escape(key)}(?![\\p{L}])",RegexOption.IGNORE_CASE).replace(t,value)
        return t
    }
    fun normalize(text: String, expandNumbers: Boolean = true): String {
        var t=abbreviations(BookTextSpacing.normalize(text))
        t=rx("(?<=[а-яёА-ЯЁ])-\\s*\\r?\\n\\s*(?=[а-яёА-ЯЁ])").replace(t,"")
        t=rx("\\[\\d{1,5}]").replace(t,"")
        for ((key,value) in mapOf("т. е." to "то есть","т.е." to "то есть","т. д." to "так далее","т.д." to "так далее","т. п." to "тому подобное","т.п." to "тому подобное","т.к." to "так как","г-н" to "господин","г-жа" to "госпожа"))
            t=rx("(?<![\\p{L}])${Regex.escape(key)}",RegexOption.IGNORE_CASE).replace(t,value)
        t=rx("([\\$€₽])\\s*(\\d+(?:[,.]\\d+)?)").replace(t) { "${it.groupValues[2]} ${it.groupValues[1]}" }
        t=RussianDates.expand(t).text
        t=rx("(?<!\\d)(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?!\\d)").replace(t) { m ->
            val h=m.groupValues[1].toLong();val min=m.groupValues[2].toLong();val sec=m.groupValues[3].toLongOrNull()
            if (h>23 || min>59 || (sec!=null && sec>59)) m.value else
                "${cardinal(h)} ${plural(h,"час","часа","часов")} ${cardinal(min,feminine=true)} ${plural(min,"минута","минуты","минут")}" +
                (sec?.let { " ${cardinal(it,feminine=true)} ${plural(it,"секунда","секунды","секунд")}" } ?: "")
        }
        // Telephone punctuation marks identify the format; ordinary long integers stay whole.
        t=rx("(?<!\\d)(?:\\+7|8)[ -]?\\(?\\d{3}\\)?[ -]\\d{3}[ -]\\d{2}[ -]\\d{2}(?!\\d)").replace(t) { m ->
            val digits=m.value.filter(Char::isDigit); val pre=if(m.value.startsWith('+')) "плюс " else ""
            pre+listOf(digits.take(1),digits.substring(1,4),digits.substring(4,7),digits.substring(7,9),digits.substring(9)).joinToString(" ") { g ->
                if (g.startsWith('0')) g.map { numbers.spellInteger((it-'0').toLong()) }.joinToString(" ") else cardinal(g.toLong())
            }
        }
        t=rx("(?<!\\d)(\\d{1,2})/(\\d{1,2})(?!\\d)").replace(t) { m ->
            val n=m.groupValues[1].toLong(); val den=m.groupValues[2].toInt()
            if (den !in 2..99) m.value else cardinal(n,feminine=true)+" "+ordinal(den,plural(n,"ая","ых","ых"))
        }
        t=rx("\\b(\\d+)-(й|ый|я|ая|е|ое|го|ого|му|ому|м|ом)\\b").replace(t) { m ->
            val n=m.groupValues[1].toIntOrNull() ?: return@replace m.value
            val ending=mapOf("й" to "ый","я" to "ая","е" to "ое","го" to "ого","му" to "ому","м" to "ом")[m.groupValues[2]] ?: m.groupValues[2]
            ordinal(n,ending)
        }
        t=rx("(?<![\\p{L}])([IVXLCDM]{1,12})(?=\\s+(?:век|глав|том|част|раздел))").replace(t) { m ->
            val vals=mapOf('I' to 1,'V' to 5,'X' to 10,'L' to 50,'C' to 100,'D' to 500,'M' to 1000)
            var n=0;var prev=0
            for(c in m.value.reversed()){val v=vals.getValue(c);n+=if(v<prev)-v else v;prev=maxOf(prev,v)}
            ordinal(n,if(t.substring(m.range.last+1).trimStart().startsWith("глава")) "ая" else "ый")
        }
        val units=mapOf("км" to listOf("километр","километра","километров"),"м" to listOf("метр","метра","метров"),"см" to listOf("сантиметр","сантиметра","сантиметров"),"мм" to listOf("миллиметр","миллиметра","миллиметров"),"кг" to listOf("килограмм","килограмма","килограммов"),"г" to listOf("грамм","грамма","граммов"),"л" to listOf("литр","литра","литров"),"руб." to listOf("рубль","рубля","рублей"),"₽" to listOf("рубль","рубля","рублей"),"$" to listOf("доллар","доллара","долларов"),"€" to listOf("евро","евро","евро"),"%" to listOf("процент","процента","процентов"),"°C" to listOf("градус Цельсия","градуса Цельсия","градусов Цельсия"),"°С" to listOf("градус Цельсия","градуса Цельсия","градусов Цельсия"),"°" to listOf("градус","градуса","градусов"))
        t=rx("(?<![\\p{L}\\d])(-?\\d+(?:[,.]\\d+)?)\\s*(${units.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }})(?![\\p{L}])").replace(t) { m ->
            val raw=m.groupValues[1]; val unit=units.getValue(m.groupValues[2]); val n=raw.toLongOrNull()
            val case=inferredCase(t.take(m.range.first))
            (if(n!=null) cardinal(n,case) else numbers.normalize(raw))+" "+if(n!=null) unitForm(n,unit,case) else unit[1]
        }
        val letterNames=mapOf('А' to "а",'Б' to "бэ",'В' to "вэ",'Г' to "гэ",'Д' to "дэ",'Е' to "е",'Ё' to "ё",'Ж' to "жэ",'З' to "зэ",'И' to "и",'Й' to "и краткое",'К' to "ка",'Л' to "эль",'М' to "эм",'Н' to "эн",'О' to "о",'П' to "пэ",'Р' to "эр",'С' to "эс",'Т' to "тэ",'У' to "у",'Ф' to "эф",'Х' to "ха",'Ц' to "цэ",'Ч' to "че",'Ш' to "ша",'Щ' to "ща",'Ъ' to "твёрдый знак",'Ы' to "ы",'Ь' to "мягкий знак",'Э' to "э",'Ю' to "ю",'Я' to "я")
        val wordAcronyms=mapOf("ВУЗ" to "вуз","НАТО" to "нато","СМИ" to "сми","ООН" to "оон")
        t=rx("(?<![\\p{L}])[А-ЯЁ]{2,8}(?![\\p{L}])").replace(t) { m ->
            wordAcronyms[m.value] ?: if(m.value in setOf("РФ","СССР","ФСБ","МВД","ДНК","РНК","ЖКХ","ГИБДД","МЧС","ТВ")) m.value.map { letterNames.getValue(it) }.joinToString(" ") else m.value
        }
        if (!expandNumbers) return t
        t=rx("(?<![\\p{L}\\d])(\\d{1,3}(?:[ \\u00a0\\u202f]\\d{3})+)(?![\\p{L}\\d])").replace(t) { it.value.filterNot(Char::isWhitespace) }
        // Case-aware ordinary numbers, after compound formats have been resolved.
        t=rx("(?<![\\p{L}\\d.,])(-?\\d{1,12})(?![\\p{L}\\d]|[.,]\\d)").replace(t) { m ->
            val case=inferredCase(t.take(m.range.first),t.substring(m.range.last+1))
            if(case==Case.NOM) {
                val expanded=numbers.prepareForLlm(m.value+t.substring(m.range.last+1))
                val number=expanded.ranges.firstOrNull()?.let { r -> expanded.text.substring(r) } ?: cardinal(m.value.toLong())
                val noun=rx("[а-яё]+").find(t.substring(m.range.last+1).lowercase())?.value.orEmpty()
                when {
                    noun !in setOf("мужчина","юноша","папа","дядя","дедушка") && (noun.endsWith('а') || noun.endsWith('я') || noun in setOf("ночь","дверь","жизнь","смерть","любовь","мышь","площадь","тетрадь","вещь","память","новость","кость")) && number.endsWith("один") -> number.removeSuffix("один")+"одна"
                    noun !in setOf("мужчина","юноша","папа","дядя","дедушка") && (noun.endsWith('а') || noun.endsWith('я') || noun in setOf("ночь","дверь","жизнь","смерть","любовь","мышь","площадь","тетрадь","вещь","память","новость","кость")) && number.endsWith("два") -> number.removeSuffix("два")+"две"
                    noun != "кофе" && (noun.endsWith('о') || noun.endsWith("ие")) && number.endsWith("один") -> number.removeSuffix("один")+"одно"
                    else -> number
                }
            } else cardinal(m.value.toLong(),case)
        }
        return numbers.normalize(t).replace(rx("[ \\t]+")," ").trim()
    }
}
