package com.brahmadeo.supertonic.tts.ambience

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.io.File

class EventVariantsTest {
    @Test fun findsAllSixVariantsInNumericOrderWithoutOtherKindsOrEmptyFiles() {
        val dir = Files.createTempDirectory("event-variants").toFile()
        try {
            val names = listOf("k_panting.m4a") + (2..6).map { "k_panting_$it.m4a" }
            (names.reversed() + listOf("k_panting_more.m4a", "k_other_4.m4a")).forEach { File(dir, it).writeText("clip") }
            File(dir, "k_panting_7.m4a").createNewFile()
            assertEquals(names, EventVariants.files(File(dir, names.first())).map { it.name })
            File(dir, "k_panting_3.m4a").delete()
            assertEquals(names.filterNot { it.endsWith("_3.m4a") }, EventVariants.files(File(dir, names.first())).map { it.name })
        } finally { dir.deleteRecursively() }
    }
    @Test fun cleanupRecognizesLaterVariantsAndPreservesActualNumericKind() {
        val known = setOf("k_panting", "k_engine_2")
        assertEquals("k_panting", EventVariants.id("k_panting_6", known))
        assertEquals("k_engine_2", EventVariants.id("k_engine_2", known))
        assertEquals("k_engine_2", EventVariants.id("k_engine_2_4", known))
        assertEquals("k_panting", EventVariants.id("k_panting", known))
    }
}
