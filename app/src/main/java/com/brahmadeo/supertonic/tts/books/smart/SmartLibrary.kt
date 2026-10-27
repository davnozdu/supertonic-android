package com.brahmadeo.supertonic.tts.books.smart

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Books of «Умная книга» (no_backup/smart_books.db): a fingerprint → (book, paragraph) index for finding the place
 * being read, the text of each book (no_backup/smart/<key>.json.gz, ~0.6 MB per novel) and the short content of its
 * sections. A book comes from a preparation with «Умная книга» (source «prepared») or from the books folder
 * («folder»); the same text from both keeps the prepared one (its sections are the ones the voices use). */
object SmartLibrary {
    const val PREPARED = "prepared"
    const val FOLDER = "folder"

    data class Book(val key: String, val title: String, val author: String, val source: String, val uri: String?,
                    val size: Long, val modified: Long, val sections: Int, val paragraphs: Int)
    data class Hit(val key: String, val paragraph: Int)

    @Volatile private var helper: Helper? = null
    @Volatile private var count = -1
    private var cached: Pair<String, SmartText>? = null

    private class Helper(context: Context) : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "smart_books.db").path, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE books(key TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT, source TEXT NOT NULL, uri TEXT, " +
                "size INTEGER, modified INTEGER, sections INTEGER, paragraphs INTEGER, layout TEXT, added INTEGER)")
            db.execSQL("CREATE TABLE prints(hash INTEGER PRIMARY KEY, key TEXT NOT NULL, paragraph INTEGER NOT NULL) WITHOUT ROWID")
            db.execSQL("CREATE INDEX prints_key ON prints(key)")
            db.execSQL("CREATE TABLE summaries(key TEXT NOT NULL, section INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY(key, section))")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    }

    private fun db(context: Context): SQLiteDatabase =
        (helper ?: synchronized(this) { helper ?: Helper(context.applicationContext).also { helper = it } }).writableDatabase

    private fun file(context: Context, key: String) = File(File(context.noBackupFilesDir, "smart").apply { mkdirs() }, "$key.json.gz")

    /** Nothing to look up: reading costs nothing while no book is added. */
    fun isEmpty(context: Context): Boolean {
        if (count < 0) count = db(context).rawQuery("SELECT count(*) FROM books", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return count == 0
    }

    /** Adds (or replaces) a book. A folder copy never replaces a prepared one. Returns false when it was kept as it was. */
    @Synchronized fun add(context: Context, key: String, text: SmartText, source: String, uri: String? = null, size: Long = 0, modified: Long = 0): Boolean {
        require(Regex("[0-9a-f]{64}").matches(key)) { "Некорректный ключ книги" }
        val db = db(context)
        val old = db.rawQuery("SELECT source, layout FROM books WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
        if (old != null && old.first == PREPARED && source == FOLDER) return false
        val target = file(context, key)
        val tmp = File(target.path + ".tmp")
        try {
            GZIPOutputStream(tmp.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { it.write(text.toJson().toString()) }
            check(tmp.renameTo(target)) { "Не удалось сохранить текст книги" }
        } finally { tmp.delete() }
        db.beginTransaction()
        try {
            db.delete("prints", "key=?", arrayOf(key))
            if (old != null && old.second != text.layout) db.delete("summaries", "key=?", arrayOf(key))
            db.insertWithOnConflict("books", null, ContentValues().apply {
                put("key", key); put("title", text.title); put("author", text.author); put("source", source); put("uri", uri)
                put("size", size); put("modified", modified); put("sections", text.sections.size); put("paragraphs", text.paragraphs)
                put("layout", text.layout); put("added", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            val insert = db.compileStatement("INSERT OR IGNORE INTO prints(hash, key, paragraph) VALUES (?, ?, ?)")
            val mark = db.compileStatement("UPDATE prints SET key='', paragraph=-1 WHERE hash=? AND NOT (key=? AND paragraph=?)")
            for ((hash, paragraph) in text.fingerprints()) {
                insert.bindLong(1, hash); insert.bindString(2, key); insert.bindLong(3, paragraph.toLong())
                if (paragraph < 0 || insert.executeInsert() == -1L) {
                    mark.bindLong(1, hash); mark.bindString(2, key); mark.bindLong(3, paragraph.toLong()); mark.executeUpdateDelete()
                }
            }
            for ((section, summary) in text.summaries) saveSummaryLocked(db, key, section, summary)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        synchronized(this) { if (cached?.first == key) cached = null }
        count = -1
        return true
    }

    @Synchronized fun remove(context: Context, key: String) {
        val db = db(context)
        db.beginTransaction()
        try {
            db.delete("prints", "key=?", arrayOf(key)); db.delete("summaries", "key=?", arrayOf(key)); db.delete("books", "key=?", arrayOf(key))
            // Sentences shared with another book are not tied to either; dropped with the book, so they never pile up
            // (the remaining books lose a few repeated sentences out of thousands).
            db.delete("prints", "key=''", null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        file(context, key).delete()
        // The file never shrinks after deletes by itself.
        runCatching { db.execSQL("VACUUM") }
        synchronized(this) { if (cached?.first == key) cached = null }
        count = -1
    }

    /** Text files without a book (a write cut off by the process dying) and leftover temporary files. */
    @Synchronized fun sweep(context: Context) {
        val keys = list(context).map { it.key }.toSet()
        File(context.noBackupFilesDir, "smart").listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp") || f.name.removeSuffix(".json.gz") !in keys) f.delete()
        }
    }

    /** Space taken: the index and the texts. */
    fun bytes(context: Context): Long = File(context.noBackupFilesDir, "smart_books.db").length() +
        (File(context.noBackupFilesDir, "smart").listFiles()?.sumOf { it.length() } ?: 0L)

    fun list(context: Context): List<Book> = db(context).rawQuery(
        "SELECT key, title, author, source, uri, size, modified, sections, paragraphs FROM books ORDER BY title", null).use { c ->
        generateSequence { if (c.moveToNext()) Book(c.getString(0), c.getString(1), c.getString(2).orEmpty(), c.getString(3), c.getString(4),
            c.getLong(5), c.getLong(6), c.getInt(7), c.getInt(8)) else null }.toList()
    }

    fun book(context: Context, key: String): Book? = list(context).firstOrNull { it.key == key }

    /** The text of a book with its saved summaries; the last one read stays in memory. */
    fun text(context: Context, key: String): SmartText? {
        synchronized(this) { cached?.takeIf { it.first == key }?.let { return it.second.copy(summaries = summaries(context, key)) } }
        val file = file(context, key)
        if (!file.isFile) return null
        val text = runCatching {
            SmartText.parse(JSONObject(GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use { it.readText() }))
        }.getOrNull() ?: return null
        synchronized(this) { cached = key to text }
        return text.copy(summaries = summaries(context, key))
    }

    fun summaries(context: Context, key: String): Map<Int, String> =
        db(context).rawQuery("SELECT section, text FROM summaries WHERE key=?", arrayOf(key)).use { c ->
            generateSequence { if (c.moveToNext()) c.getInt(0) to c.getString(1) else null }.toMap()
        }

    @Synchronized fun saveSummary(context: Context, key: String, section: Int, text: String) = saveSummaryLocked(db(context), key, section, text)

    private fun saveSummaryLocked(db: SQLiteDatabase, key: String, section: Int, text: String) {
        db.insertWithOnConflict("summaries", null, ContentValues().apply { put("key", key); put("section", section); put("text", text) },
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun lookup(context: Context, hashes: List<Long>): List<Hit> {
        if (hashes.isEmpty()) return emptyList()
        val db = db(context)
        return hashes.mapNotNull { h ->
            db.rawQuery("SELECT key, paragraph FROM prints WHERE hash=?", arrayOf(h.toString())).use { c ->
                if (c.moveToFirst() && c.getInt(1) >= 0) Hit(c.getString(0), c.getInt(1)) else null
            }
        }
    }
}
