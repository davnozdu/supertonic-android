package com.brahmadeo.supertonic.tts.books.prepare

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BookQueuePathTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "mytts-path-test/book-queue")

    @Test fun validSourceRestoresItsEntryWithoutRamId() {
        assertEquals(File(root, "123").canonicalFile, BookQueuePath.directory(root, File(root, "123/book.epub")))
    }
    @Test fun substringOfQueuePathDoesNotAuthorizeAnotherDirectory() {
        assertNull(BookQueuePath.directory(root, File(root.parentFile, "other/book-queue/123/book.epub")))
        assertNull(BookQueuePath.directory(root, File(root, "book.epub")))
        assertNull(BookQueuePath.directory(root, File(root, "123/nested/book.epub")))
    }
    @Test fun parentTraversalCannotEscapeQueue() {
        assertNull(BookQueuePath.directory(root, File(root, "../outside/book.epub")))
        assertEquals(File(root, "123").canonicalFile, BookQueuePath.directory(root, File(root, "123/./book.epub")))
    }
}
