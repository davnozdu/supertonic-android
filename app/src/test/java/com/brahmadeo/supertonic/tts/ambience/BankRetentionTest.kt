package com.brahmadeo.supertonic.tts.ambience

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.io.File
import java.security.MessageDigest

class BankRetentionTest {
    @Test fun updateKeepsMatchingAndCustomButDropsChangedAndRemoved() {
        val dir = Files.createTempDirectory("bank-retention").toFile()
        try {
            for (name in listOf("same.m4a", "changed.m4a", "removed.m4a", "custom.m4a", "manifest.json")) File(dir, name).writeText("old")
            val sha = MessageDigest.getInstance("SHA-256").digest("old".toByteArray()).joinToString("") { "%02x".format(it) }
            assertEquals(2, BankRetention.prune(dir, mapOf("same.m4a" to sha, "changed.m4a" to "different", "new.m4a" to sha), setOf("custom.m4a")))
            assertEquals(setOf("same.m4a", "custom.m4a", "manifest.json"), dir.list()!!.toSet())
            assertEquals(0, BankRetention.prune(dir, mapOf("same.m4a" to sha), setOf("custom.m4a")))
        } finally { dir.deleteRecursively() }
    }
}
