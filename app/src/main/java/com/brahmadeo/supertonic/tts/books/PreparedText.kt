package com.brahmadeo.supertonic.tts.books

import org.json.JSONObject

/** Готовый текст книги: ударения, ё и пунктуация, посчитанные заранее при подготовке и записанные в сам файл книги
 * (book.json, раздел «prepared»). Ключ — отпечаток предложения по всем его буквам, значение — само предложение в
 * подготовленном виде. Пока книга подготовлена, при чтении нейросеть для текста не нужна: он берётся отсюда.
 * Текст, которого в файле нет или который разошёлся по буквам, читается обычным путём — облаком (если оно есть)
 * или словарями. */
object PreparedText {
    const val ALGORITHM = "fnv1a64-all"
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

    /** Отпечаток предложения по всем его буквам: короткие реплики («—») тоже получают свой ключ. */
    fun key(sentence: String): Long = BookFingerprint.hash(BookFingerprint.letters(sentence))

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

    /** Готовый текст [text] книги или null, если хотя бы одно предложение не найдено или разошлось по буквам. */
    fun prepare(store: Map<Long, String>, text: String): String? {
        if (store.isEmpty() || text.isBlank()) return null
        // Целый абзац одной записью: так ложатся абзацы, где модель слила или разбила предложения.
        store[key(text)]?.let { whole ->
            if (BookFingerprint.letters(whole) == BookFingerprint.letters(text)) return whole
        }
        val parts = pieces(text)
        if (parts.isEmpty()) return null
        val out = StringBuilder(text.length + text.length / 5)
        for (part in parts) {
            val got = store[key(part.text)] ?: return null
            // Слова и буквы обязаны совпасть: иначе это ответ для другого издания или испорченный файл.
            if (BookFingerprint.letters(got) != BookFingerprint.letters(part.text)) return null
            out.append(got).append(part.separator)
        }
        return out.toString()
    }
}
