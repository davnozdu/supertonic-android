package com.brahmadeo.supertonic.tts.books

import android.content.Context
import org.json.JSONObject
import java.io.File

/** «Контрольный слепок» названия звука: одинаковый набор слов даёт одинаковое число, порядок слов не важен,
 * синонимы уже сведены в [EventKinds.canon]. По слепку приложение узнаёт один и тот же звук, даже если модель
 * назвала его иначе («дверь открылась» / «открылась дверь» / «open_door» вместо «door_open»), и дописывает новые
 * слепки как синонимы — со временем попадание становится точным без правки кода. */
object Aliases {
    /** Слепок названия: FNV-1a 64 по каноническим словам в нижнем регистре. */
    fun fingerprint(text: String): Long {
        var hash = -0x340d631b7bdddcdbL // 14695981039346656037
        for (ch in EventKinds.canon(text)) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }

    /** Ключ вида по выученным синонимам (null — такого слепка ещё нет). */
    fun resolve(text: String): String? = learned[textKey(text)]

    /** Запомнить, что [raw] — это вид [kind]; сохраняется на телефоне (файл sound-bank/aliases.json). */
    fun learn(raw: String, kind: String) {
        val name = raw.trim()
        if (name.isEmpty() || kind.isEmpty()) return
        val key = textKey(name)
        if (learned.put(key, kind) == null) {
            dirty = true
            save?.let { runCatching { persist(it) } }
        }
    }

    /** Прочитать выученные синонимы; вызывается при подготовке книги и при старте чтения. */
    @Synchronized
    fun load(context: Context) {
        val file = File(context.filesDir, "sound-bank/aliases.json")
        if (!file.isFile) { save = context.applicationContext; return }
        runCatching {
            val o = JSONObject(file.readText())
            o.keys().forEach { k -> o.optString(k).takeIf { it.isNotEmpty() }?.let { learned[k] = it } }
        }
        save = context.applicationContext
    }

    private fun textKey(text: String) = fingerprint(text).toString()

    private fun persist(context: Context) {
        val file = File(context.filesDir, "sound-bank/aliases.json")
        file.parentFile?.mkdirs()
        val json = JSONObject()
        learned.forEach { (k, v) -> json.put(k, v) }
        file.writeText(json.toString())
    }

    private val learned = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var dirty = false
    @Volatile private var save: Context? = null
}
