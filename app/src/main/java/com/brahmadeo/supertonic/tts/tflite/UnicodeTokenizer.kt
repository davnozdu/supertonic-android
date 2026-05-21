package com.brahmadeo.supertonic.tts.tflite

import org.json.JSONArray
import java.io.File
import java.text.Normalizer

/**
 * Kotlin port of the Rust UnicodeProcessor in helper.rs. Reads
 * unicode_indexer.json (a flat array of 65536 int64s where index = unicode
 * codepoint and value = token id, or -1 for "missing -> 0 fallback") and
 * tokenizes user text identically to the Rust pipeline.
 *
 * preprocess_text() here covers the universal dash/quote normalization and
 * non-English whitespace path. The English-specific symbol replacement
 * branch and English emoji stripping are not ported because the only
 * caller right now (the INT4 hybrid pipeline) is targeted at Russian.
 */
class UnicodeTokenizer(indexerFile: File) {

    private val indexer: IntArray = run {
        val arr = JSONArray(indexerFile.readText())
        IntArray(arr.length()) { arr.getInt(it) }
    }

    private val universalReplacements = listOf(
        "–" to "-",       // en dash
        "‑" to "-",       // non-breaking hyphen
        "—" to ", ",      // em dash -> natural pause
        "“" to "\"",       // left double quote
        "”" to "\"",       // right double quote
        "‘" to "'",       // left single quote
        "’" to "'",       // right single quote
        "«" to "\"",       // guillemet open
        "»" to "\"",       // guillemet close
    )

    private val whitespace = Regex("\\s+")

    /**
     * Tokenize one text string at a given language tag (e.g. "ru"). Returns
     * the padded fixed-length text_ids, the float text_mask of equal length,
     * and the number of real (non-padding) tokens.
     */
    fun tokenize(text: String, lang: String, fixedLen: Int): TokenizeResult {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKD)
        for ((from, to) in universalReplacements) t = t.replace(from, to)
        t = whitespace.replace(t, " ").trim()
        val processed = "<$lang>$t</$lang>"

        // Iterate by code points; the indexer covers the BMP plane.
        val ids = ArrayList<Int>(processed.length)
        var i = 0
        while (i < processed.length) {
            val cp = processed.codePointAt(i)
            val id = if (cp < indexer.size) {
                val raw = indexer[cp]
                if (raw == -1) 0 else raw
            } else 0
            ids.add(id)
            i += Character.charCount(cp)
        }

        val validLen = ids.size.coerceAtMost(fixedLen)
        val out = LongArray(fixedLen)
        for (k in 0 until validLen) out[k] = ids[k].toLong()
        val mask = FloatArray(fixedLen) { if (it < validLen) 1f else 0f }
        return TokenizeResult(out, mask, validLen)
    }

    data class TokenizeResult(val textIds: LongArray, val textMask: FloatArray, val validLen: Int)
}
