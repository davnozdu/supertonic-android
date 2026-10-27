package com.brahmadeo.supertonic.tts.books.smart

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.brahmadeo.supertonic.tts.books.prepare.BookStructure
import com.brahmadeo.supertonic.tts.books.prepare.EpubBook
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** The books folder of «Умная книга»: every EPUB / FB2 / FB2.ZIP in it (and its subfolders) is read without any LLM —
 * its chapters, text and fingerprints — so a book opened in a reader is recognised by its text. Unchanged files (same
 * size and date) are not read again; removed files leave the library. */
object SmartFolder {
    const val PREF_FOLDER = "smart_book_folder"
    private const val PREF_SCANNED = "smart_book_scanned"
    private const val MAX_FILES = 3000
    private const val MAX_DEPTH = 6
    const val PREF_HOURS = "smart_book_rescan_hours"
    const val PREF_UNKNOWN = "smart_book_rescan_unknown"
    /** Choices of the interval, hours; 0 — only by hand and for an unknown book. */
    val HOURS = listOf(0, 1, 3, 6, 12, 24)
    private const val DEFAULT_HOURS = 6
    /** An unknown book being read is looked for in the folder at most this often. */
    private const val UNKNOWN_MIN_MS = 5 * 60 * 1000L

    data class State(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val current: String = "", val message: String = "")
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private fun prefs(context: Context) = context.getSharedPreferences("SupertonicPrefs", Context.MODE_PRIVATE)
    fun folder(context: Context): Uri? = prefs(context).getString(PREF_FOLDER, null)?.let(Uri::parse)

    fun choose(context: Context, tree: Uri) {
        val ctx = context.applicationContext
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        folder(ctx)?.takeIf { it != tree }?.let { old -> runCatching { ctx.contentResolver.releasePersistableUriPermission(old, flags) } }
        ctx.contentResolver.takePersistableUriPermission(tree, flags)
        prefs(ctx).edit().putString(PREF_FOLDER, tree.toString()).apply()
        scan(ctx)
    }

    /** Forget the folder: its books leave the library; prepared books stay. */
    fun clear(context: Context) {
        val ctx = context.applicationContext
        cancel()
        folder(ctx)?.let { runCatching { ctx.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        prefs(ctx).edit().remove(PREF_FOLDER).remove(PREF_SCANNED).apply()
        scope.launch {
            for (b in SmartLibrary.list(ctx)) if (b.source == SmartLibrary.FOLDER) { SmartLibrary.remove(ctx, b.key); SmartMatcher.forget(ctx, b.key) }
            mutable.value = State(message = "Папка отключена.")
        }
    }

    fun hours(context: Context) = prefs(context).getInt(PREF_HOURS, DEFAULT_HOURS)
    fun scansUnknown(context: Context) = prefs(context).getBoolean(PREF_UNKNOWN, true)

    /** When the engine starts and while reading: the folder is read again after the chosen interval. Only a listing
     * when nothing changed: unchanged files are not opened. */
    fun scanIfDue(context: Context) {
        val ctx = context.applicationContext
        val hours = hours(ctx)
        if (hours <= 0 || folder(ctx) == null || !SmartMatcher.enabled(ctx)) return
        if (System.currentTimeMillis() - prefs(ctx).getLong(PREF_SCANNED, 0) >= hours * 3_600_000L) scan(ctx)
    }

    /** A book being read is not in the library: it may have just been put into the folder. */
    fun scanForUnknown(context: Context) {
        val ctx = context.applicationContext
        if (!scansUnknown(ctx) || folder(ctx) == null) return
        if (System.currentTimeMillis() - prefs(ctx).getLong(PREF_SCANNED, 0) >= UNKNOWN_MIN_MS) scan(ctx)
    }

    @Synchronized fun cancel() { job?.cancel() }

    @Synchronized fun scan(context: Context) {
        if (job?.isActive == true) return
        val ctx = context.applicationContext
        val tree = folder(ctx) ?: return
        mutable.value = State(true, message = "Поиск книг…")
        job = scope.launch {
            var added = 0; var failed = 0
            try {
                SmartLibrary.sweep(ctx)
                val files = list(ctx, tree)
                val known = SmartLibrary.list(ctx).filter { it.source == SmartLibrary.FOLDER }
                val byUri = known.associateBy { it.uri }
                // Removed or replaced files leave the library.
                val present = files.map { it.uri.toString() }.toSet()
                for (b in known) if (b.uri !in present) { SmartLibrary.remove(ctx, b.key); SmartMatcher.forget(ctx, b.key) }
                val todo = files.filter { f -> byUri[f.uri.toString()].let { it == null || it.size != f.size || it.modified != f.modified } }
                todo.forEachIndexed { i, f ->
                    ensureActive()
                    mutable.value = State(true, i, todo.size, f.name)
                    if (runCatching { read(ctx, f) }.onFailure { if (it is CancellationException) throw it }.getOrDefault(false)) added++ else failed++
                }
                prefs(ctx).edit().putLong(PREF_SCANNED, System.currentTimeMillis()).apply()
                val total = SmartLibrary.list(ctx).size
                mutable.value = State(message = "Книг в «Умной книге»: $total. " + (if (added > 0) "Добавлено или обновлено: $added. " else "") +
                    (if (failed > 0) "Не удалось прочитать: $failed." else ""))
            } catch (_: CancellationException) {
                mutable.value = State(message = "Обновление остановлено.")
            } catch (_: SecurityException) {
                mutable.value = State(message = "Нет доступа к папке. Выберите её снова.")
            } catch (_: Exception) {
                mutable.value = State(message = "Не удалось прочитать папку. Выберите её снова.")
            } finally { synchronized(this@SmartFolder) { job = null } }
        }
    }

    private data class Entry(val uri: Uri, val name: String, val size: Long, val modified: Long)

    private fun bookName(name: String) = name.lowercase().let { it.endsWith(".epub") || it.endsWith(".fb2") || it.endsWith(".fb2.zip") }

    private fun list(ctx: Context, tree: Uri): List<Entry> {
        val out = mutableListOf<Entry>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree) to 0))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        while (pending.isNotEmpty() && out.size < MAX_FILES) {
            val (parent, depth) = pending.removeFirst()
            ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), columns, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < MAX_FILES) {
                    val id = c.getString(0); val name = c.getString(1).orEmpty()
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { if (depth < MAX_DEPTH) pending += id to depth + 1; continue }
                    val size = if (c.isNull(3)) -1 else c.getLong(3)
                    if (bookName(name) && size in 1..EpubBook.MAX_BYTES)
                        out += Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, size, if (c.isNull(4)) 0 else c.getLong(4))
                }
            }
        }
        return out
    }

    /** Chapters and text of one file, as the preparation reads them (a collection stays in its table of contents). */
    private fun read(ctx: Context, f: Entry): Boolean {
        val temp = File.createTempFile("smart-book-", ".book", ctx.cacheDir)
        try {
            ctx.contentResolver.openInputStream(f.uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: return false
            val book = BookStructure.chapters(temp)
            if (book.paragraphs.isEmpty()) return false
            val text = SmartText.of(book.title.ifBlank { f.name.substringBeforeLast('.') }, book.author, book.sections.map { it.title },
                book.paragraphs.map { it.section to it.text })
            val key = SmartText.contentKey(book.paragraphs.map { it.text })
            // A changed file is another text (another key): its old entry goes.
            for (old in SmartLibrary.list(ctx)) if (old.source == SmartLibrary.FOLDER && old.uri == f.uri.toString() && old.key != key) {
                SmartLibrary.remove(ctx, old.key); SmartMatcher.forget(ctx, old.key)
            }
            SmartLibrary.add(ctx, key, text, SmartLibrary.FOLDER, f.uri.toString(), f.size, f.modified)
            return true
        } finally { temp.delete() }
    }
}
