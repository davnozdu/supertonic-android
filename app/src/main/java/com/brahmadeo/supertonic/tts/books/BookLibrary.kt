package com.brahmadeo.supertonic.tts.books

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** Prepared books on the phone (no_backup/books.db): the `.mytts-book` file as imported and a
 * fingerprint → (book, section) index for recognising the book in text from Moon+. ~0.2 MB per novel;
 * a lookup is ~10 µs. A fingerprint present in two books or sections points nowhere (book = -1). */
object BookLibrary {
    data class Entry(val id: Long, val title: String, val author: String, val characters: Int, val sections: Int, val added: Long)
    data class Hit(val book: Long, val section: String)

    private const val AMBIGUOUS = -1L
    @Volatile private var helper: Helper? = null
    private val packages = java.util.concurrent.ConcurrentHashMap<Long, BookPackage>()

    private class Helper(context: Context) : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "books.db").path, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT, content_sha TEXT UNIQUE NOT NULL, " +
                "file_sha TEXT, title TEXT NOT NULL, author TEXT, characters INTEGER, sections INTEGER, added INTEGER, json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE fingerprints(hash INTEGER PRIMARY KEY, book INTEGER NOT NULL, section TEXT NOT NULL) WITHOUT ROWID")
            db.execSQL("CREATE INDEX fingerprints_book ON fingerprints(book)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    }

    private fun db(context: Context): SQLiteDatabase =
        (helper ?: synchronized(this) { helper ?: Helper(context.applicationContext).also { helper = it } }).writableDatabase

    /** Imports (or replaces the same text: same content_sha256) a book; returns its id. */
    @Synchronized fun import(context: Context, json: String): Long {
        val pkg = BookPackage.parse(json)
        val db = db(context)
        db.beginTransaction()
        try {
            db.rawQuery("SELECT id FROM books WHERE content_sha=?", arrayOf(pkg.contentSha256)).use { if (it.moveToFirst()) removeLocked(db, it.getLong(0)) }
            val id = db.insertOrThrow("books", null, ContentValues().apply {
                put("content_sha", pkg.contentSha256); put("file_sha", pkg.fileSha256); put("title", pkg.title)
                put("author", pkg.author); put("characters", pkg.groups.indices.sumOf { pkg.groupCast(it).characters.size })
                put("sections", pkg.sections.size); put("added", System.currentTimeMillis()); put("json", json)
            })
            val insert = db.compileStatement("INSERT OR IGNORE INTO fingerprints(hash, book, section) VALUES (?, ?, ?)")
            val mark = db.compileStatement("UPDATE fingerprints SET book=$AMBIGUOUS WHERE hash=? AND NOT (book=? AND section=?)")
            for ((section, hashes) in pkg.fingerprints) for (h in hashes) {
                insert.bindLong(1, h); insert.bindLong(2, id); insert.bindString(3, section)
                if (insert.executeInsert() == -1L) { mark.bindLong(1, h); mark.bindLong(2, id); mark.bindString(3, section); mark.executeUpdateDelete() }
            }
            db.setTransactionSuccessful()
            packages[id] = pkg
            return id
        } finally { db.endTransaction() }
    }

    @Synchronized fun remove(context: Context, id: Long) {
        val db = db(context)
        db.beginTransaction()
        try { removeLocked(db, id); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    private fun removeLocked(db: SQLiteDatabase, id: Long) {
        db.delete("fingerprints", "book=?", arrayOf(id.toString()))
        db.delete("books", "id=?", arrayOf(id.toString()))
        packages.remove(id)
    }

    fun list(context: Context): List<Entry> = db(context).rawQuery(
        "SELECT id, title, author, characters, sections, added FROM books ORDER BY added DESC", null).use { c ->
        generateSequence { if (c.moveToNext()) Entry(c.getLong(0), c.getString(1), c.getString(2).orEmpty(), c.getInt(3), c.getInt(4), c.getLong(5)) else null }.toList()
    }

    /** The file as imported, for «Поделиться». */
    fun json(context: Context, id: Long): String? =
        db(context).rawQuery("SELECT json FROM books WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }

    fun get(context: Context, id: Long): BookPackage? = packages[id] ?: json(context, id)?.let { BookPackage.parse(it) }?.also { packages[id] = it }

    fun lookup(context: Context, hashes: List<Long>): List<Hit> {
        if (hashes.isEmpty()) return emptyList()
        val db = db(context)
        return hashes.mapNotNull { h ->
            db.rawQuery("SELECT book, section FROM fingerprints WHERE hash=?", arrayOf(h.toString())).use { c ->
                if (c.moveToFirst() && c.getLong(0) != AMBIGUOUS) Hit(c.getLong(0), c.getString(1)) else null
            }
        }
    }
}
