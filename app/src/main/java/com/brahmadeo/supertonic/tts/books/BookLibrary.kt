package com.brahmadeo.supertonic.tts.books

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** Prepared books on the phone (no_backup/books.db): the `.mytts-book` file as imported and a
 * fingerprint → (book, section) index for recognising the book in text from Moon+. ~0.2 MB per novel;
 * a lookup is ~10 µs. A sentence shared by several books (the same chapter in a collection and in the novel) points
 * to all of them; BookMatcher decides by the reading and the other sentences. Inside one book a sentence repeated
 * in two sections keeps the first. */
object BookLibrary {
    data class Entry(val id: Long, val title: String, val author: String, val characters: Int, val sections: Int, val added: Long)
    data class Hit(val book: Long, val section: String)
    @Volatile private var helper: Helper? = null
    private val packages = java.util.concurrent.ConcurrentHashMap<Long, BookPackage>()

    private class Helper(context: Context) : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "books.db").path, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT, content_sha TEXT UNIQUE NOT NULL, " +
                "file_sha TEXT, title TEXT NOT NULL, author TEXT, characters INTEGER, sections INTEGER, added INTEGER, json TEXT NOT NULL)")
            createPrints(db)
        }
        /** v1 kept one book per sentence and lost the shared ones: the index is rebuilt from the stored books. */
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            if (old < 2) {
                db.execSQL("DROP TABLE IF EXISTS fingerprints")
                createPrints(db)
                val ids = db.rawQuery("SELECT id FROM books", null).use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() }
                for (id in ids) runCatching { addPrints(db, id, BookPackage.parse(readJson(db, id) ?: return@runCatching)) }
            }
        }
        private fun createPrints(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE fingerprints(hash INTEGER NOT NULL, book INTEGER NOT NULL, section TEXT NOT NULL, PRIMARY KEY(hash, book)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX fingerprints_book ON fingerprints(book)")
        }
    }

    private fun addPrints(db: SQLiteDatabase, id: Long, pkg: BookPackage) {
        val insert = db.compileStatement("INSERT OR IGNORE INTO fingerprints(hash, book, section) VALUES (?, ?, ?)")
        for ((section, hashes) in pkg.fingerprints) for (h in hashes) {
            insert.bindLong(1, h); insert.bindLong(2, id); insert.bindString(3, section); insert.executeInsert()
        }
    }

    private fun db(context: Context): SQLiteDatabase =
        (helper ?: synchronized(this) { helper ?: Helper(context.applicationContext).also { helper = it } }).writableDatabase

    /** Imports (or replaces the same text: same content_sha256) a book; returns its id. */
    @Synchronized fun import(context: Context, file: String): Long {
        // «Умная книга»: the book text travels in the file but is kept apart (SmartLibrary), not in every parse of the package.
        var json = file
        val smart = if (file.contains("\"smart\"")) org.json.JSONObject(file).let { o ->
            o.optJSONObject("smart")?.also { o.remove("smart"); json = o.toString() }
        } else null
        val pkg = BookPackage.parse(json)
        val smartText = smart?.let { runCatching { com.brahmadeo.supertonic.tts.books.smart.SmartText.parse(it) }.getOrNull() }
        val db = db(context)
        db.beginTransaction()
        try {
            // The same text again (a newer preparation, «Разметка реплик» added): updated in place, so the book keeps its
            // id and with it the models and voices chosen for it.
            val existing = db.rawQuery("SELECT id FROM books WHERE content_sha=?", arrayOf(pkg.contentSha256)).use { if (it.moveToFirst()) it.getLong(0) else null }
            val values = ContentValues().apply {
                put("content_sha", pkg.contentSha256); put("file_sha", pkg.fileSha256); put("title", pkg.title)
                put("author", pkg.author); put("characters", pkg.groups.indices.sumOf { pkg.groupCast(it).characters.size })
                put("sections", pkg.sections.size); put("json", json)
            }
            val id = if (existing != null) {
                db.delete("fingerprints", "book=?", arrayOf(existing.toString()))
                db.update("books", values, "id=?", arrayOf(existing.toString()))
                packages.remove(existing)
                existing
            } else db.insertOrThrow("books", null, values.apply { put("added", System.currentTimeMillis()) })
            addPrints(db, id, pkg)
            db.setTransactionSuccessful()
            packages[id] = pkg
            // A text that cannot be saved does not undo the voices: the book is simply not «умная».
            smartText?.let { runCatching { com.brahmadeo.supertonic.tts.books.smart.SmartLibrary.add(context, pkg.contentSha256, it,
                com.brahmadeo.supertonic.tts.books.smart.SmartLibrary.PREPARED) } }
            return id
        } finally { db.endTransaction() }
    }

    @Synchronized fun remove(context: Context, id: Long) {
        val db = db(context)
        // The text saved with this book («Умная книга») goes with it; a copy from the books folder stays.
        contentSha(context, id)?.let { sha ->
            BookFiles.remove(context, sha) // its book file and location sounds
            com.brahmadeo.supertonic.tts.books.PreparedText.forget(context, sha) // its prepared text (ударения, ё)
            val smart = com.brahmadeo.supertonic.tts.books.smart.SmartLibrary
            if (smart.book(context, sha)?.source == smart.PREPARED) { smart.remove(context, sha); com.brahmadeo.supertonic.tts.books.smart.SmartMatcher.forget(context, sha) }
        }
        db.beginTransaction()
        try { removeLocked(db, id); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    private fun removeLocked(db: SQLiteDatabase, id: Long) {
        db.delete("fingerprints", "book=?", arrayOf(id.toString()))
        db.delete("books", "id=?", arrayOf(id.toString()))
        packages.remove(id); shas.remove(id)
    }

    fun list(context: Context): List<Entry> = db(context).rawQuery(
        "SELECT id, title, author, characters, sections, added FROM books ORDER BY added DESC", null).use { c ->
        generateSequence { if (c.moveToNext()) Entry(c.getLong(0), c.getString(1), c.getString(2).orEmpty(), c.getInt(3), c.getInt(4), c.getLong(5)) else null }.toList()
    }

    private val shas = java.util.concurrent.ConcurrentHashMap<Long, String>()
    /** content_sha256 of book [id]: the same text keeps it through a regeneration or a delete and new import, so the
     * voices chosen for the book are stored under it (BookVoices), not under the row id. */
    fun contentSha(context: Context, id: Long): String? = shas[id] ?: db(context).rawQuery("SELECT content_sha FROM books WHERE id=?",
        arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }?.also { shas[id] = it }
    /** Every book: row id -> content_sha256. */
    fun contentShas(context: Context): Map<Long, String> = db(context).rawQuery("SELECT id, content_sha FROM books", null).use { c ->
        generateSequence { if (c.moveToNext()) c.getLong(0) to c.getString(1) else null }.toMap()
    }

    /** Characters per read: a row is read through a CursorWindow of ~2 MB, and a novel with «Разметка реплик» is
     * larger («Идиот»: 2.5 MB). Read whole, it threw SQLiteBlobTooBigException after a restart, so the book was not
     * recognised, its voices were not shown and its replies could not be marked again. */
    private const val CHUNK = 200_000

    /** The file as imported, for «Поделиться». Read in pieces (see [CHUNK]). */
    fun json(context: Context, id: Long): String? = readJson(db(context), id)

    private fun readJson(db: SQLiteDatabase, id: Long): String? {
        val key = id.toString()
        val length = db.rawQuery("SELECT length(json) FROM books WHERE id=?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        val out = StringBuilder(length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var start = 1L
        while (start <= length) {
            db.rawQuery("SELECT substr(json, ?, ?) FROM books WHERE id=?", arrayOf(start.toString(), CHUNK.toString(), key)).use {
                if (it.moveToFirst()) out.append(it.getString(0)) else return null
            }
            start += CHUNK
        }
        return out.toString()
    }

    /** book.json to share (inside the [BookArchive] container): as imported, with the text of «Умная книга» when it
     * was saved with the book. */
    fun export(context: Context, id: Long): String? {
        val json = json(context, id) ?: return null
        val sha = contentSha(context, id) ?: return json
        val smart = com.brahmadeo.supertonic.tts.books.smart.SmartLibrary
        if (smart.book(context, sha)?.source != smart.PREPARED) return json
        val text = smart.text(context, sha) ?: return json
        return org.json.JSONObject(json).put("smart", text.toJson()).toString()
    }

    fun get(context: Context, id: Long): BookPackage? = packages[id] ?: json(context, id)?.let { BookPackage.parse(it) }?.also { packages[id] = it }

    /** «Разметка реплик» of a book (empty when it has none). */
    fun replies(context: Context, id: Long): ReplyIndex = runCatching { get(context, id)?.replies }.getOrNull() ?: ReplyIndex.EMPTY

    /** «Фоновые звуки локаций» of a book (empty when it has none). */
    fun scenes(context: Context, id: Long): SceneIndex = runCatching { get(context, id)?.scenes }.getOrNull() ?: SceneIndex.EMPTY

    /** Per fingerprint found: every book (and its section) that has it, newest book first. */
    fun lookup(context: Context, hashes: List<Long>): List<List<Hit>> {
        if (hashes.isEmpty()) return emptyList()
        val db = db(context)
        return hashes.mapNotNull { h ->
            db.rawQuery("SELECT f.book, f.section FROM fingerprints f JOIN books b ON b.id = f.book WHERE f.hash=? ORDER BY b.added DESC",
                arrayOf(h.toString())).use { c ->
                generateSequence { if (c.moveToNext()) Hit(c.getLong(0), c.getString(1)) else null }.toList()
            }.takeIf { it.isNotEmpty() }
        }
    }
}
