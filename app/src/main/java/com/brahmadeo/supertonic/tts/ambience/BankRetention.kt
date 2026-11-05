package com.brahmadeo.supertonic.tts.ambience

import java.io.File
import java.security.MessageDigest

/** Keep unchanged official clips and every locally generated/imported clip across bank updates. */
object BankRetention {
    fun prune(dir: File, expected: Map<String, String>, local: Set<String>): Int {
        var removed = 0
        dir.listFiles()?.filter { it.isFile && it.extension == "m4a" && it.name !in local }?.forEach { file ->
            val wanted = expected[file.name]
            val same = wanted?.takeIf { it.isNotEmpty() }?.let { hash ->
                runCatching {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }.equals(hash, ignoreCase = true)
                }.getOrDefault(false)
            } ?: false
            if (!same && file.delete()) removed++
        }
        return removed
    }
}
