package com.brahmadeo.supertonic.tts.local

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shared offline Silero Stress: context BERT for homographs, word accentor, safe ё dictionary.
 * Existing explicit stress (including LLM/user lexicon) and written ё are never overwritten. */
object LocalRussianStress {
    private var data: JSONObject? = null
    private var yoDictionary: com.brahmadeo.supertonic.tts.utils.BinaryAccentDictionary? = null
    private var accentor: Module? = null
    private var homo: Module? = null
    private var lastUsed = 0L
    private val words = Regex("[+А-Яа-яЁё\\u0301]+")
    private const val vowels = "аеёиоуыэюя"
    private val cache = object : LinkedHashMap<String, String>(128, .75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String,String>?) = size > 512
    }
    init {
        Executors.newSingleThreadScheduledExecutor { Thread(it, "LocalStressIdle").apply { isDaemon = true } }
            .scheduleWithFixedDelay({ synchronized(this) {
                if (accentor != null && android.os.SystemClock.elapsedRealtime() - lastUsed > 120000) {
                    accentor?.destroy(); homo?.destroy(); accentor = null; homo = null; data = null; yoDictionary?.close(); yoDictionary = null
                    Log.i("LocalRussian", "Stress models unloaded after idle")
                }
            } }, 15, 15, TimeUnit.SECONDS)
    }
    private fun load(ctx: Context) {
        if (data != null) return
        val root = LocalRussianAssets.root(ctx)
        val json = JSONObject(File(root, "stress.json").readText())
        try {
            accentor = LiteModuleLoader.load(File(root,"accentor.ptl").path)
            homo = LiteModuleLoader.load(File(root,"homo.ptl").path)
            yoDictionary = com.brahmadeo.supertonic.tts.utils.BinaryAccentDictionary.open(File(root,"yo.sacc"))
            data = json
        } catch (t: Throwable) { accentor?.destroy(); homo?.destroy(); accentor = null; homo = null; throw t }
    }
    // Standard greedy WordPiece, with the homograph markers kept as special tokens.
    private fun encode(text: String, bert: JSONObject): LongArray {
        val vocab = bert.getJSONObject("vocab")
        val ids = mutableListOf(bert.getLong("cls"))
        val tokens = Regex("\\[/?HOMO\\]|[\\p{L}\\p{N}]+|[^\\p{L}\\p{N}\\s]").findAll(text).map { it.value }
        for (token in tokens) {
            if (vocab.has(token)) { ids += vocab.getLong(token); continue }
            val pieces = mutableListOf<Long>(); var start = 0
            while (start < token.length) {
                var end = token.length
                var found: String? = null
                while (end > start) {
                    val piece = (if (start > 0) "##" else "") + token.substring(start,end)
                    if (vocab.has(piece)) { found = piece; break }; end--
                }
                if (found == null) { pieces.clear(); pieces += bert.getLong("unk"); break }
                pieces += vocab.getLong(found); start = end
            }
            ids += pieces
        }
        ids += bert.getLong("sep")
        return ids.toLongArray()
    }
    private fun restoreCase(original: String, variant: String): String {
        var pos = 0
        return variant.map { c ->
            if (c == '+') c else c.let { if (original.getOrNull(pos++)?.isUpperCase() == true) it.uppercaseChar() else it }
        }.joinToString("")
    }
    @Synchronized fun apply(ctx: Context, text: String): String {
        if (!LocalRussianAssets.ready(ctx) || !ctx.getSharedPreferences("SupertonicPrefs",0).getBoolean("local_russian_stress",true)) return text
        cache[text]?.let { return it }
        val started = android.os.SystemClock.elapsedRealtime()
        lastUsed = started
        return try {
            load(ctx)
            val d = data!!; val exceptions = d.getJSONObject("exceptions")
            val homodict = d.getJSONObject("homodict"); val bert = d.getJSONObject("bert")
            val candidates = words.findAll(text).filter { m ->
                val word=m.value; val lower=word.lowercase()
                '+' !in word && '\u0301' !in word && 'ё' !in lower && lower.count { it in vowels } > 1 &&
                    !exceptions.has(lower) && !homodict.has(lower) &&
                    com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.apply(word,"ru") == word && yoDictionary?.lookup(word.toByteArray(Charsets.UTF_8)) == null
            }.toList()
            val predicted = mutableMapOf<Int,Pair<FloatArray,FloatArray>>()
            if(candidates.isNotEmpty()) {
                val out=accentor!!.forward(IValue.listFrom(*candidates.map { IValue.from(it.value.lowercase()) }.toTypedArray())).toTuple()
                val st=out[0].toTensor().dataAsFloatArray;val yp=out[1].toTensor().dataAsFloatArray
                val stWidth=st.size/candidates.size;val yoWidth=yp.size/candidates.size
                candidates.forEachIndexed { i,m -> predicted[m.range.first]=st.copyOfRange(i*stWidth,(i+1)*stWidth) to yp.copyOfRange(i*yoWidth,(i+1)*yoWidth) }
            }
            val result = words.replace(text) { m ->
                val original = m.value
                if ('+' in original || '\u0301' in original || 'ё' in original || 'Ё' in original) return@replace original
                val lower = original.lowercase()
                // Capitalized personal names remain names, never guessed as a different ё-name.
                val properNames=mapOf("Лене" to "Л+ене","Дарье" to "Д+арье","Пети" to "П+ети","Алле" to "+Алле","Инге" to "+Инге")
                properNames[original]?.let { return@replace it }
                if (original == "Семена" && text.take(m.range.first).trimEnd().lastOrNull()?.let { it !in ".!?…" } == true)
                    return@replace "Семёна"
                yoDictionary?.lookup(original.toByteArray(Charsets.UTF_8))?.let { return@replace it }
                if (lower == "письма" && Regex("текст\\s+$",RegexOption.IGNORE_CASE).containsMatchIn(text.take(m.range.first)))
                    return@replace restoreCase(original,"письм+а")
                val variants = homodict.optJSONArray(lower)
                if (variants != null && variants.length() == 2) {
                    val left = text.substring(maxOf(0,m.range.first-150),m.range.first).replace("+", "").replace("\u0301", "")
                    val right = text.substring(m.range.last+1,minOf(text.length,m.range.last+151)).replace("+", "").replace("\u0301", "")
                    val marked = "${left.takeIf { it.isNotBlank() }?.let { it[0].uppercase()+it.substring(1).lowercase() } ?: ""} [HOMO] $lower [/HOMO] ${right.lowercase()}"
                    val ids = encode(marked,bert)
                    val start = ids.indexOf(bert.getLong("homo_start")); val end = ids.indexOf(bert.getLong("homo_end"))
                    if (start >= 0 && end > start && ids.size <= 512) {
                        val score = homo!!.forward(IValue.from(Tensor.fromBlob(ids,longArrayOf(1,ids.size.toLong()))),
                            IValue.from(Tensor.fromBlob(longArrayOf(start.toLong()),longArrayOf(1))),
                            IValue.from(Tensor.fromBlob(longArrayOf(end.toLong()),longArrayOf(1)))).toTensor().dataAsFloatArray[0]
                        val choices = (0..1).map { variants.getString(it) }.sorted()
                        return@replace restoreCase(original, choices[if (score > 0f) 1 else 0])
                    }
                }
                val positions = lower.indices.filter { lower[it] in vowels }
                if (positions.isEmpty()) return@replace original
                val ex = exceptions.optJSONArray(lower)
                if (ex != null) {
                    val stress = ex.getInt(0); val yoAt = ex.getInt(1)
                    var value = original
                    if (yoAt in value.indices && value[yoAt].lowercaseChar() == 'е')
                        value = value.substring(0,yoAt) + (if (value[yoAt].isUpperCase()) "Ё" else "ё") + value.substring(yoAt+1)
                    return@replace if (stress in value.indices) value.substring(0,stress)+"+"+value.substring(stress) else value
                }
                if (positions.size == 1) return@replace original
                val dictionary = com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager.apply(original,"ru")
                if(dictionary != original) return@replace dictionary
                val prediction = predicted[m.range.first] ?: return@replace original
                val st = prediction.first
                val yp = prediction.second
                val index = positions.indices.maxByOrNull { st[it] } ?: 0
                val pos = positions[index]
                var value = original
                // Neural ё only on the stressed vowel; named entities stay conservative.
                val yoIndex = yp.indices.maxByOrNull { yp[it] } ?: -1
                val maxYo = yp.maxOrNull() ?: 0f
                val confidence = if (yp.isEmpty()) 0.0 else 1.0 / yp.sumOf { kotlin.math.exp((it-maxYo).toDouble()) }
                if (yoIndex == index && confidence > .5 && value[pos].lowercaseChar() == 'е' && original[0].isLowerCase())
                    value = value.substring(0,pos)+"ё"+value.substring(pos+1)
                value.substring(0,pos)+"+"+value.substring(pos)
            }
            cache[text] = result
            Log.i("LocalRussian", "Offline stress/yo chars=${text.length} ms=${android.os.SystemClock.elapsedRealtime()-started}")
            result
        } catch (t: Throwable) {
            Log.e("LocalRussian", "Offline accentor failed; existing dictionary remains active", t); text
        } finally { lastUsed = android.os.SystemClock.elapsedRealtime() }
    }
}
