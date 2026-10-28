package com.brahmadeo.supertonic.tts.ambience

import java.io.File
import java.util.PriorityQueue

/** SentencePiece BPE encoder for the Gemma tokenizer that Stable Audio 3's T5Gemma uses: identity normalizer,
 * spaces escaped to ▁, no dummy prefix, user-defined pieces matched whole, byte fallback. Same ids as
 * sentencepiece's Encode (checked against the Python library on English prompts in Sa3TokenizerTest). */
class Sa3Tokenizer(bytes: ByteArray) {
    private val ids = HashMap<String, Int>(1 shl 19)
    private val scores: FloatArray
    private val userDefined = HashSet<String>()
    private var longestUser = 1
    private val byteIds = IntArray(256) { -1 }

    init {
        val list = ArrayList<Triple<String, Float, Int>>(256_000)
        var p = 0
        while (p < bytes.size) {
            val (key, next) = varint(bytes, p); p = next
            val field = (key ushr 3).toInt(); val wire = (key and 7).toInt()
            if (wire == 2) {
                val (len, start) = varint(bytes, p); p = start + len.toInt()
                if (field == 1) list.add(piece(bytes, start, p))
            } else p = skip(bytes, p, wire)
        }
        scores = FloatArray(list.size)
        list.forEachIndexed { id, (text, score, type) ->
            scores[id] = score
            when (type) {
                NORMAL -> ids.putIfAbsent(text, id)
                USER_DEFINED -> { ids.putIfAbsent(text, id); userDefined.add(text); longestUser = maxOf(longestUser, text.length) }
                BYTE -> text.removePrefix("<0x").removeSuffix(">").toIntOrNull(16)?.let { byteIds[it] = id }
            }
        }
        require(list.size > 1000 && byteIds.all { it >= 0 }) { "Not a byte-fallback SentencePiece model" }
    }

    constructor(file: File) : this(file.readBytes())

    fun encode(text: String): IntArray {
        val norm = text.replace(' ', '▁')
        val symbols = ArrayList<String>()
        var i = 0
        while (i < norm.length) {
            var taken = 0
            for (len in minOf(longestUser, norm.length - i) downTo 1) if (norm.substring(i, i + len) in userDefined) { taken = len; break }
            if (taken == 0) taken = Character.charCount(norm.codePointAt(i))
            symbols.add(norm.substring(i, i + taken)); i += taken
        }
        val text0 = symbols.toTypedArray<String?>()
        val prev = IntArray(text0.size) { it - 1 }
        val next = IntArray(text0.size) { if (it + 1 < text0.size) it + 1 else -1 }
        // Highest score first, then the leftmost pair (sentencepiece's agenda order).
        val agenda = PriorityQueue<Pair>(compareByDescending<Pair> { it.score }.thenBy { it.left })
        fun add(left: Int, right: Int) {
            if (left < 0 || right < 0) return
            val merged = text0[left] + text0[right]
            val id = ids[merged] ?: return
            agenda.add(Pair(left, right, scores[id], merged))
        }
        for (k in 0 until text0.size - 1) add(k, k + 1)
        while (agenda.isNotEmpty()) {
            val top = agenda.poll()!!
            val l = text0[top.left]; val r = text0[top.right]
            if (l == null || r == null || next[top.left] != top.right || l + r != top.merged) continue
            text0[top.left] = top.merged; text0[top.right] = null
            next[top.left] = next[top.right]
            if (next[top.right] >= 0) prev[next[top.right]] = top.left
            add(prev[top.left], top.left); add(top.left, next[top.left])
        }
        val out = ArrayList<Int>()
        var k = 0
        while (k >= 0 && k < text0.size) {
            val s = text0[k]!!
            val id = ids[s]
            if (id != null) out.add(id) else s.toByteArray(Charsets.UTF_8).forEach { out.add(byteIds[it.toInt() and 255]) }
            k = next[k]
        }
        return out.toIntArray()
    }

    private class Pair(val left: Int, val right: Int, val score: Float, val merged: String)

    private companion object {
        const val NORMAL = 1; const val USER_DEFINED = 4; const val BYTE = 6

        fun varint(b: ByteArray, start: Int): kotlin.Pair<Long, Int> {
            var p = start; var shift = 0; var v = 0L
            while (true) {
                val c = b[p++].toInt() and 255
                v = v or ((c and 127).toLong() shl shift)
                if (c < 128) return v to p
                shift += 7
            }
        }

        fun skip(b: ByteArray, p: Int, wire: Int) = when (wire) {
            0 -> varint(b, p).second
            1 -> p + 8
            5 -> p + 4
            else -> error("Unsupported protobuf wire type $wire")
        }

        /** SentencePiece { 1: piece, 2: score (float), 3: type (enum, NORMAL when absent) }. */
        fun piece(b: ByteArray, start: Int, end: Int): Triple<String, Float, Int> {
            var p = start; var text = ""; var score = 0f; var type = NORMAL
            while (p < end) {
                val (key, next) = varint(b, p); p = next
                when ((key ushr 3).toInt() to (key and 7).toInt()) {
                    1 to 2 -> { val (len, s) = varint(b, p); text = String(b, s, len.toInt(), Charsets.UTF_8); p = s + len.toInt() }
                    2 to 5 -> { score = java.lang.Float.intBitsToFloat((b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8) or
                        ((b[p + 2].toInt() and 255) shl 16) or ((b[p + 3].toInt() and 255) shl 24)); p += 4 }
                    3 to 0 -> { val (v, n) = varint(b, p); type = v.toInt(); p = n }
                    else -> p = skip(b, p, (key and 7).toInt())
                }
            }
            return Triple(text, score, type)
        }
    }
}
