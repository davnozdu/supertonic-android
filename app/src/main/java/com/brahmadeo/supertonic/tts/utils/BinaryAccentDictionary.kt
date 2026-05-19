package com.brahmadeo.supertonic.tts.utils

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Memory-mapped read-only accent dictionary backed by a `.sacc` v1 file.
 *
 * Format (mirrors `dictionaries/build/build_binary.py`):
 *
 * ```
 * Header (28 bytes, little-endian):
 *   0:  magic           u8[4]  = "SACC"
 *   4:  version         u32    = 1
 *   8:  entry_count     u32
 *   12: offsets_offset  u64    (= 28)
 *   20: data_offset     u64    (= 28 + entry_count * 4)
 *
 * Offsets table (entry_count × 4 bytes, little-endian u32):
 *   each entry is the offset of that record relative to data_offset.
 *
 * Data section (entry_count entries, sorted by lowercased UTF-8 key):
 *   u16 key_len, u16 value_len, <key_len bytes>, <value_len bytes>
 * ```
 *
 * Compared to the JSON HashMap backend in [AccentDictionaryManager], this:
 *  - never loads the dictionary into the JVM heap. The page cache holds
 *    whatever is hot; cold pages are paged in by the kernel on demand.
 *  - opens in <50 ms regardless of size — there's no parsing, just an
 *    `mmap` of an already-prepared layout.
 *  - serves lookups by binary search on the offsets table, comparing
 *    UTF-8 bytes against the query (no String allocations per probe).
 *
 * Lookup latency is ~10× slower than a HashMap (a few µs vs <1 µs per
 * call) — utterly invisible at the scale of a sentence with ~10 words.
 */
class BinaryAccentDictionary private constructor(
    private val raf: RandomAccessFile,
    private val buffer: ByteBuffer,
    val entryCount: Int,
    private val offsetsOffset: Long,
    private val dataOffset: Long
) {

    // ByteBuffer is NOT thread-safe (position/limit/mark are mutable state),
    // so readValueAt used to call buffer.duplicate() per lookup to get a
    // private view. With thousands of word lookups per synthesized sentence,
    // that's thousands of small allocations on the hot path. A ThreadLocal
    // duplicate amortises it — each thread that ever touches the dictionary
    // builds one view, then reuses it forever.
    private val threadLocalView: ThreadLocal<ByteBuffer> = ThreadLocal.withInitial {
        buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    }

    /**
     * Look up [lowerKey] (expected to already be lowercased and ready as
     * UTF-8 bytes). Returns the stressed-form value, or null if not present.
     */
    fun lookup(lowerKey: ByteArray): String? {
        if (entryCount == 0 || lowerKey.isEmpty()) return null
        var lo = 0
        var hi = entryCount - 1
        while (lo <= hi) {
            val mid = (lo + hi).ushr(1)
            val entryAbs = entryAbsoluteOffset(mid)
            val cmp = compareKeyAt(entryAbs, lowerKey)
            when {
                cmp < 0 -> lo = mid + 1   // stored key < query, go right
                cmp > 0 -> hi = mid - 1   // stored key > query, go left
                else -> return readValueAt(entryAbs)
            }
        }
        return null
    }

    /** Resolve the absolute byte position of the i-th entry inside [buffer]. */
    private fun entryAbsoluteOffset(i: Int): Int {
        val tableEntryAbs = (offsetsOffset + i.toLong() * 4L).toInt()
        val rel = buffer.getInt(tableEntryAbs).toLong() and 0xFFFFFFFFL
        return (dataOffset + rel).toInt()
    }

    /**
     * Three-way compare: stored key vs the [query] bytes. Returns < 0 if the
     * stored key sorts before the query, > 0 if after, 0 if equal.
     */
    private fun compareKeyAt(entryAbs: Int, query: ByteArray): Int {
        val keyLen = buffer.getShort(entryAbs).toInt() and 0xFFFF
        val keyStart = entryAbs + 4
        val cmpLen = minOf(keyLen, query.size)
        for (j in 0 until cmpLen) {
            val a = buffer.get(keyStart + j).toInt() and 0xFF
            val b = query[j].toInt() and 0xFF
            if (a != b) return a - b
        }
        return keyLen - query.size
    }

    private fun readValueAt(entryAbs: Int): String {
        val keyLen = buffer.getShort(entryAbs).toInt() and 0xFFFF
        val valLen = buffer.getShort(entryAbs + 2).toInt() and 0xFFFF
        val valStart = entryAbs + 4 + keyLen
        val bytes = ByteArray(valLen)
        // Use the thread-local view so we don't allocate a fresh duplicate
        // for every lookup. Each thread mutates its own view's position
        // freely; the underlying mmap'd buffer is never touched.
        val view = threadLocalView.get()!!
        view.position(valStart)
        view.get(bytes, 0, valLen)
        return String(bytes, Charsets.UTF_8)
    }

    fun close() {
        try {
            raf.close()
        } catch (e: Exception) {
            Log.w(TAG, "close failed", e)
        }
    }

    companion object {
        private const val TAG = "BinaryAccentDict"

        // Header constants — every byte position is fixed by the file format.
        private const val HEADER_SIZE = 28
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), 'C'.code.toByte())
        private const val SUPPORTED_VERSION = 1
        private const val MAX_MAPPED_BYTES = 2_147_483_647L // ByteBuffer addresses are int — keep < 2 GiB

        /**
         * Check whether [file] starts with the SACC magic header. Cheap (5
         * bytes off disk) so the caller can probe before committing to a
         * full mmap.
         */
        fun looksLikeSacc(file: File): Boolean {
            if (!file.exists() || file.length() < HEADER_SIZE) return false
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val buf = ByteArray(4)
                    raf.readFully(buf)
                    buf.contentEquals(MAGIC)
                }
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Open [file] as a memory-mapped .sacc dictionary. Returns null and
         * logs on any I/O or format error so the caller can fall back to the
         * JSON code path.
         */
        fun open(file: File): BinaryAccentDictionary? {
            var raf: RandomAccessFile? = null
            return try {
                val size = file.length()
                if (size < HEADER_SIZE) {
                    Log.w(TAG, "file too small: ${file.absolutePath}")
                    return null
                }
                if (size > MAX_MAPPED_BYTES) {
                    Log.w(TAG, "file > 2 GiB, ByteBuffer addressing won't fit: $size")
                    return null
                }
                raf = RandomAccessFile(file, "r")
                val channel = raf.channel
                val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0L, size)
                    .order(ByteOrder.LITTLE_ENDIAN)
                // Magic
                val magic = ByteArray(4).also { for (i in 0..3) it[i] = buffer.get(i) }
                if (!magic.contentEquals(MAGIC)) {
                    Log.w(TAG, "bad magic: ${magic.joinToString("") { "%02x".format(it) }}")
                    raf.close()
                    return null
                }
                val version = buffer.getInt(4)
                if (version != SUPPORTED_VERSION) {
                    Log.w(TAG, "unsupported version: $version")
                    raf.close()
                    return null
                }
                val entryCount = buffer.getInt(8)
                val offsetsOffset = buffer.getLong(12)
                val dataOffset = buffer.getLong(20)
                if (entryCount < 0 ||
                    offsetsOffset < HEADER_SIZE ||
                    dataOffset < offsetsOffset + entryCount.toLong() * 4L ||
                    dataOffset > size
                ) {
                    Log.w(TAG, "header values out of range: count=$entryCount " +
                        "offsets@$offsetsOffset data@$dataOffset size=$size")
                    raf.close()
                    return null
                }
                Log.i(TAG, "opened .sacc: $entryCount entries (${size / 1_048_576.0} MB)")
                BinaryAccentDictionary(raf, buffer, entryCount, offsetsOffset, dataOffset)
            } catch (e: Throwable) {
                Log.e(TAG, "open failed: ${file.absolutePath}", e)
                try { raf?.close() } catch (_: Exception) {}
                null
            }
        }
    }
}
