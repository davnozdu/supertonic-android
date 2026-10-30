package com.brahmadeo.supertonic.tts.books

import org.json.JSONObject

/** Готовый текст книги: ударения, ё и пунктуация, посчитанные заранее при подготовке и записанные в сам файл книги
 * (book.json, раздел «prepared»). Ключ — отпечаток предложения по всем его буквам, значение — само предложение в
 * подготовленном виде. Пока книга подготовлена, при чтении нейросеть для текста не нужна: он берётся отсюда.
 * Текст, которого в файле нет или который разошёлся по буквам, читается обычным путём — облаком (если оно есть)
 * или словарями. */
object PreparedText {
    const val ALGORITHM = "fnv1a64-marks"
    const val MAX_ENTRIES = 400_000
    const val MAX_CHARS = 200_000_000L
    /** Предложение заканчивается на . ! ? … — так же, как их режет [BookFingerprint]. */
    private val separators = Regex("(?<=[.!?…])[\\s\\p{Z}\\u0085\\u001c-\\u001f]+")

    /** Предложение и то, что шло за ним (пробелы, перевод строки): при сборке текста всё возвращается на место. */
    data class Piece(val text: String, val separator: String)

    fun pieces(text: String): List<Piece> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Piece>()
        var start = 0
        for (m in separators.findAll(text)) {
            out.add(Piece(text.substring(start, m.range.first), text.substring(m.range.first, m.range.last + 1)))
            start = m.range.last + 1
        }
        if (start < text.length) out.add(Piece(text.substring(start), ""))
        return out
    }

    /** Авторские знаки: точка, запятая, тире, двоеточие, точка с запятой, многоточие, вопросительный и восклицательный.
     * Модель может добавить свой знак, но ни один авторский пропасть не должен — он и есть интонация и паузы. */
    private const val MARKS = ",;:—–.!?…"

    /** Все авторские знаки на месте и в том же порядке (добавленные между ними не мешают).
     * Тире в начале строки — типографский знак реплики, а не авторская пунктуация внутри фразы: читалки его
     * присылают и с ним, и без него («— Да.» / «Да.»), поэтому у начала строки он сравнивается нестрого. */
    fun keepsPunctuation(source: String, stored: String): Boolean {
        fun marks(text: String): String {
            val body = text.trimStart().let { if (it.startsWith("—") || it.startsWith("–") || it.startsWith("-")) it.substring(1) else it }
            return body.filter { it in MARKS }
        }
        val want = marks(source)
        val have = marks(stored)
        var j = 0
        for (ch in want) {
            while (j < have.length && have[j] != ch) j++
            if (j >= have.length) return false
            j++
        }
        return true
    }

    /** Отпечаток предложения: все буквы и все авторские знаки. Букв мало — «Анна!» и «Анна»» имели бы один ключ,
     * и второе предложение читалось бы текстом первого. Тире в начале строки в отпечаток не входит: читалки
     * присылают реплику и с ним, и без него. */
    fun key(sentence: String): Long = BookFingerprint.hash(BookFingerprint.letters(sentence) + "\u0001" + signature(sentence))

    /** Авторские знаки предложения без тире в начале строки (знак реплики, а не пунктуация внутри фразы). */
    fun signature(text: String): String {
        val body = text.trimStart().let { if (it.startsWith("—") || it.startsWith("–") || it.startsWith("-")) it.substring(1) else it }
        return body.filter { it in MARKS }
    }

    /** Пара «отпечаток → текст» для предложения книги и его подготовленной версии; null, если слова разошлись. */
    fun pair(source: String, prepared: String): Pair<Long, String>? {
        if (source.isBlank() || prepared.isBlank()) return null
        if (BookFingerprint.letters(source) != BookFingerprint.letters(prepared)) return null
        return key(source) to prepared
    }

    /** Предложения абзаца: исходное и подготовленное по одному на каждое; при разном числе — абзац целиком. */
    fun pairs(source: String, prepared: String): List<Pair<Long, String>> {
        val from = pieces(source)
        val to = pieces(prepared)
        if (from.size != to.size || from.isEmpty()) return listOfNotNull(pair(source, prepared.replace("\n", " ")))
        return from.indices.mapNotNull { pair(from[it].text, to[it].text) }
    }

    /** Набор книги из book.json: {algorithm, texts: {"<hex>": "предложение"}}. */
    fun parse(o: JSONObject?): Map<Long, String> {
        o ?: return emptyMap()
        if (o.optString("algorithm") != ALGORITHM) return emptyMap()
        val texts = o.optJSONObject("texts") ?: return emptyMap()
        val map = HashMap<Long, String>(minOf(texts.length(), MAX_ENTRIES) * 2)
        var chars = 0L
        for (hex in texts.keys()) {
            if (map.size >= MAX_ENTRIES || chars > MAX_CHARS) break
            // Ключи пишутся как беззнаковые (java.lang.Long.toHexString): отрицательные хеши тоже читаются.
            val hash = hex.toULongOrNull(16)?.toLong() ?: continue
            val value = texts.optString(hex)
            if (value.isBlank()) continue
            map[hash] = value
            chars += value.length
        }
        return map
    }

    /** Раздел «prepared» файла книги. */
    fun toJson(store: Map<Long, String>): JSONObject {
        val texts = JSONObject()
        for ((hash, value) in store) texts.put(java.lang.Long.toHexString(hash), value)
        return JSONObject().put("algorithm", ALGORITHM).put("texts", texts)
    }

    /** Начало строки берём из ИСХОДНОГО предложения: тире реплики («— Анна!») — знак автора, и читалки присылают
     * реплику и с ним, и без него. Так «— Да.» остаётся репликой, а «Да.» не получает лишнего тире. */
    fun alignLeadingDash(source: String, stored: String): String {
        val lead = source.takeWhile { it.isWhitespace() }
        val rest = source.substring(lead.length)
        fun isDash(c: Char?) = c == '—' || c == '–' || c == '-'
        val dash = if (isDash(rest.firstOrNull())) rest.substring(0, 1) else ""
        val afterDash = if (dash.isEmpty()) "" else rest.substring(1).takeWhile { it.isWhitespace() }
        var body = stored.trimStart()
        if (isDash(body.firstOrNull())) body = body.substring(1).trimStart()
        return lead + dash + afterDash + body
    }

    /** Готовый текст [text] книги или null, если хотя бы одно предложение не найдено или разошлось по буквам. */
    fun prepare(store: Map<Long, String>, text: String): String? {
        if (store.isEmpty() || text.isBlank()) return null
        // Целый абзац одной записью: так ложатся абзацы, где модель слила или разбила предложения.
        store[key(text)]?.let { whole ->
            if (BookFingerprint.letters(whole) == BookFingerprint.letters(text) && keepsPunctuation(text, whole)) return whole
        }
        val parts = pieces(text)
        if (parts.isEmpty()) return null
        val out = StringBuilder(text.length + text.length / 5)
        for (part in parts) {
            val got = store[key(part.text)] ?: return null
            // Слова и буквы обязаны совпасть: иначе это ответ для другого издания или испорченный файл.
            if (BookFingerprint.letters(got) != BookFingerprint.letters(part.text)) return null
            // И авторская пунктуация — тоже: точка, запятая, тире не могут исчезнуть по дороге.
            if (!keepsPunctuation(part.text, got)) return null
            // Тире реплики в начале строки — типографский знак автора: если оно было, оно остаётся на месте.
            out.append(alignLeadingDash(part.text, got)).append(part.separator)
        }
        return out.toString()
    }
}
