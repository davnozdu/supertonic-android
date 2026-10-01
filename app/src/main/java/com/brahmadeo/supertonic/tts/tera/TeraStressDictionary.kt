package com.brahmadeo.supertonic.tts.tera

import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import com.brahmadeo.supertonic.tts.utils.BinaryAccentDictionary
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

/** Converts the large upstream JSON dictionary into the app's mmap-backed SACC format. */
object TeraStressDictionary {
    private const val SACC_NAME = "accents.sacc"
    private const val HEADER_BYTES = 28

    fun databaseFile(root: File) = File(root, SACC_NAME)

    fun prepare(root: File) {
        val destination = databaseFile(root)
        if (destination.exists() && BinaryAccentDictionary.looksLikeSacc(destination)) return
        val stagingDb = File(root, "accents-sort.db")
        val stagingSacc = File(root, "$SACC_NAME.part")
        stagingDb.delete()
        stagingSacc.delete()
        val database = SQLiteDatabase.openOrCreateDatabase(stagingDb, null)
        try {
            // SQLite is a temporary external sort. Synthesis uses the SACC
            // binary search reader without a SQLite connection or Java map.
            database.execSQL("PRAGMA journal_mode=OFF")
            database.execSQL("PRAGMA synchronous=OFF")
            database.execSQL("CREATE TABLE accents (word TEXT NOT NULL, stressed TEXT NOT NULL)")
            val insert = database.compileStatement("INSERT INTO accents(word, stressed) VALUES (?, ?)")
            try {
                var count = 0
                database.beginTransaction()
                try {
                    JsonReader(InputStreamReader(GZIPInputStream(FileInputStream(File(root, "ruaccent/dictionary/accents.json.gz"))), Charsets.UTF_8)).use { reader ->
                        reader.beginObject()
                        while (reader.hasNext()) {
                            insert.clearBindings()
                            insert.bindString(1, reader.nextName())
                            insert.bindString(2, reader.nextString())
                            insert.executeInsert()
                            count++
                            if (count % 50_000 == 0) {
                                database.setTransactionSuccessful()
                                database.endTransaction()
                                database.beginTransaction()
                            }
                        }
                        reader.endObject()
                    }
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            } finally {
                insert.close()
            }
            database.execSQL("CREATE INDEX accents_word ON accents(word COLLATE BINARY)")

            val count = database.rawQuery("SELECT COUNT(*) FROM accents", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            require(count > 0)
            val dataOffset = HEADER_BYTES + count.toLong() * 4L
            RandomAccessFile(stagingSacc, "rw").use { file ->
                file.setLength(dataOffset)
                val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                header.put(byteArrayOf('S'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), 'C'.code.toByte()))
                header.putInt(1)
                header.putInt(count)
                header.putLong(HEADER_BYTES.toLong())
                header.putLong(dataOffset)
                file.channel.write(ByteBuffer.wrap(header.array()), 0L)
                val offsets = file.channel.map(FileChannel.MapMode.READ_WRITE, HEADER_BYTES.toLong(), count.toLong() * 4L)
                    .order(ByteOrder.LITTLE_ENDIAN)
                BufferedOutputStream(FileOutputStream(stagingSacc, true), 256 * 1024).use { output ->
                    var relativeOffset = 0L
                    var index = 0
                    database.rawQuery("SELECT word, stressed FROM accents ORDER BY word COLLATE BINARY", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val key = cursor.getString(0).toByteArray(Charsets.UTF_8)
                            val value = cursor.getString(1).toByteArray(Charsets.UTF_8)
                            require(key.size <= 65535 && value.size <= 65535 && relativeOffset <= 0xffffffffL)
                            offsets.putInt(index * 4, relativeOffset.toInt())
                            output.write(key.size and 255)
                            output.write(key.size ushr 8)
                            output.write(value.size and 255)
                            output.write(value.size ushr 8)
                            output.write(key)
                            output.write(value)
                            relativeOffset += 4 + key.size + value.size
                            index++
                        }
                    }
                    check(index == count)
                }
            }
        } catch (t: Throwable) {
            database.close()
            stagingDb.delete()
            stagingSacc.delete()
            throw t
        }
        database.close()
        stagingDb.delete()
        check(stagingSacc.renameTo(destination)) { "Could not finish TeraTTS stress index" }
    }
}

class TeraStressLookup(root: File) : AutoCloseable {
    private val dictionary = requireNotNull(BinaryAccentDictionary.open(TeraStressDictionary.databaseFile(root))) {
        "TeraTTS stress index is missing or invalid"
    }

    fun lookup(word: String): String? = dictionary.lookup(word.toByteArray(Charsets.UTF_8))

    override fun close() = dictionary.close()
}
