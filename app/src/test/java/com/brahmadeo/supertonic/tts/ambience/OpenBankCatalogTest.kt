package com.brahmadeo.supertonic.tts.ambience

import org.junit.Assert.*
import org.junit.Test

/** Pinned metadata for every shipped recording; no Android or network dependency. */
class OpenBankCatalogTest {
    private fun rows(name: String) = javaClass.getResourceAsStream("/open-$name-v1.tsv")!!
        .bufferedReader().use { it.readLines() }.map { it.split('\t') }
    private fun scenes() = rows("scenes").map { r -> OpenBankMatch.Scene(r[0], emptySet(),
        r[3].split(',').toSet(), r[2], r[1].toDouble()) }
    @Test fun completeCatalogsArePresent() {
        assertEquals(378, scenes().size)
        assertEquals(2136, rows("events").size)
        assertEquals(378, scenes().map { it.id }.toSet().size)
        assertEquals(2136, rows("events").map { it[0] }.toSet().size)
    }
    @Test fun entireSceneCatalogRejectsKnownFalseUrbanMatches() {
        val all = scenes()
        val outdoor = OpenBankMatch.scenes("narrow stone ledge high on a skyscraper, wind buffeting wall, city traffic below", all)
        assertTrue(outdoor.isNotEmpty())
        for (id in outdoor) {
            val scene = all.single { it.id == id }
            assertEquals("street", OpenBankMatch.habitat(scene.title))
            assertTrue(scene.seconds >= 30.0)
        }
        assertFalse(outdoor.any { it in setOf("0997", "0998", "1066", "0080", "0081", "0090", "3541", "3538", "3539") })
        val indoor = OpenBankMatch.scenes("luxury penthouse interior, carpet, city traffic below glass", all)
        assertTrue(indoor.isNotEmpty())
        assertTrue(indoor.all { it in setOf("0080", "0081", "2387", "2388", "0750") })
    }
    @Test fun basicHabitatsFindSustainedRecordingsAcrossWholeCatalog() {
        for (query in listOf("forest with birds", "city street traffic", "sea waves on beach", "mountain stream", "restaurant", "outdoor market", "wind")) {
            assertTrue(query, OpenBankMatch.scenes(query, scenes()).isNotEmpty())
        }
        assertTrue(OpenBankMatch.scenes("abandoned cellar with pipes", scenes()).isEmpty())
    }
    @Test fun handSawNeverSelectsAMotorizedSaw() {
        val selected = rows("events").filter { OpenEventMatch.matches("saw", it[0], it[2].split(',').toSet()) }
        assertEquals(setOf("0016", "0559"), selected.map { it[0] }.toSet())
    }
    @Test fun eventOverridesPointOnlyToExistingRecordings() {
        val all = rows("events")
        for (kind in listOf("clock_tick", "gunshot", "rifle_shot", "shotgun_shot", "door_slide", "chair", "paper", "match_strike", "electric_switch", "sigh")) {
            assertTrue(kind, all.any { OpenEventMatch.matches(kind, it[0], emptySet()) })
        }
        // Audited false positives stay rejected for every recording, not just the first variant.
        for (kind in listOf("computer_beeps", "fishing_reel", "flies", "pig", "children_play", "wine", "horn", "bird", "typewriter", "door_grind", "box_open", "coin", "sneeze")) {
            assertFalse(kind, all.any { OpenEventMatch.matches(kind, it[0], it[2].split(',').toSet()) })
        }
    }
}
