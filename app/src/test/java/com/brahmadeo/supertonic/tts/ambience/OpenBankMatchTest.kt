package com.brahmadeo.supertonic.tts.ambience

import org.junit.Assert.*
import org.junit.Test

class OpenBankMatchTest {
    private val forest = OpenBankMatch.Scene("forest", setOf("forest", "birds"), setOf("forest", "birds", "morning"), "Forest", 60.0)
    private val rain = OpenBankMatch.Scene("rainforest", setOf("forest", "rain"), setOf("forest", "rain"), "Forest in rain", 60.0)
    private val street = OpenBankMatch.Scene("street", setOf("street"), setOf("street", "traffic", "cars"), "London Street", 60.0)

    @Test fun forestDoesNotSelectStreet() {
        assertEquals(listOf("forest"), OpenBankMatch.scenes("quiet forest with birds", listOf(street, forest)))
    }
    @Test fun rainMustActuallyBeInTheRecording() {
        assertEquals(listOf("rainforest"), OpenBankMatch.scenes("rain in a forest", listOf(forest, rain)))
        assertEquals(listOf("forest"), OpenBankMatch.scenes("dry quiet forest", listOf(forest, rain)))
    }
    @Test fun missingLocationFallsBackToGenerator() {
        assertTrue(OpenBankMatch.scenes("an abandoned cellar with distant pipes", listOf(forest, street)).isEmpty())
    }
    @Test fun nightRejectsMorningBirds() {
        assertTrue(OpenBankMatch.scenes("forest at midnight", listOf(forest)).isEmpty())
    }
    @Test fun historicalStreetRejectsModernTraffic() {
        assertTrue(OpenBankMatch.scenes("medieval street with horses", listOf(street)).isEmpty())
    }
    @Test fun noVoicesIsRespected() {
        val crowd = OpenBankMatch.Scene("cafe", setOf("restaurant"), setOf("voices", "conversation"), "Cafe", 60.0)
        assertTrue(OpenBankMatch.scenes("cafe without voices", listOf(crowd)).isEmpty())
    }
    @Test fun equivalentVariantsAreNotRejectedAsAmbiguous() {
        assertEquals(2, OpenBankMatch.scenes("quiet forest", listOf(forest, forest.copy(id = "forest2"))).size)
    }

    @Test fun incidentalCityTagsCannotTurnAnimalsIntoStreet() {
        val frogs = street.copy(id = "frogs", title = "Frogs #1", tags = street.tags + "frog")
        val tram = street.copy(id = "tram", title = "Tram Passing #2")
        val metro = street.copy(id = "metro", title = "Metro, Paris #1")
        assertEquals(listOf("street"), OpenBankMatch.scenes("city street traffic", listOf(frogs, tram, metro, street)))
    }
    @Test fun highLedgeIsOutsideAndPenthouseIsInside() {
        val apartment = street.copy(id = "inside", title = "Paris Apartment. Closed Window.")
        assertEquals(listOf("street"), OpenBankMatch.scenes("narrow stone ledge high on a skyscraper, wind and city below", listOf(street, apartment)))
        assertEquals(listOf("inside"), OpenBankMatch.scenes("high penthouse interior, distant city traffic below glass", listOf(street, apartment)))
        assertEquals(listOf("street"), OpenBankMatch.scenes("open balcony high above the city", listOf(street, apartment)))
    }
    @Test fun separateEventsDoNotChangeHabitat() {
        assertEquals(listOf("forest"), OpenBankMatch.scenes("quiet forest | a distant train passes | a phone rings", listOf(street, forest)))
    }
    @Test fun bedsRequireAtLeastThirtySeconds() {
        assertTrue(OpenBankMatch.scenes("city street", listOf(street.copy(seconds = 29.9))).isEmpty())
        assertTrue(OpenBankMatch.scenes("city street", listOf(street.copy(seconds = 0.0))).isEmpty())
    }
    @Test fun rainyDescriptionRejectsDryQueryEvenIfConceptIsMissing() {
        val rainy = street.copy(tags = street.tags + "rainy")
        assertTrue(OpenBankMatch.scenes("dry street", listOf(rainy)).isEmpty())
        assertEquals(listOf("street"), OpenBankMatch.scenes("rainy street in rain", listOf(rainy)))
    }
    @Test fun eventCorrectionsPreserveActionMeaning() {
        assertFalse(OpenEventMatch.matches("clock", "0927", setOf("clock")))
        assertFalse(OpenEventMatch.matches("computer_beeps", "1298", setOf("computer_beeps")))
        assertFalse(OpenEventMatch.matches("pistol_shot", "2854", setOf("pistol_shot")))
        assertTrue(OpenEventMatch.matches("shotgun_shot", "2854", emptySet()))
        assertTrue(OpenEventMatch.matches("gunshot", "0438", emptySet()))
        assertFalse(OpenEventMatch.matches("sigh_relief", "1405", emptySet()))
        assertFalse(OpenEventMatch.matches("sit_down_chair", "0666", emptySet()))
        assertTrue(OpenEventMatch.matches("phone", "0250", setOf("phone")))
    }

    @Test fun explicitAbsenceNeverBecomesPositiveSearchTerm() {
        assertEquals(listOf("forest"), OpenBankMatch.scenes("forest without rain", listOf(forest, rain)))
        assertTrue(OpenBankMatch.scenes("street without traffic", listOf(street)).isEmpty())
        assertTrue(OpenBankMatch.scenes("forest without birds", listOf(forest)).isEmpty())
    }
    @Test fun harborBirdCallsAreNotGeneralHarborAmbience() {
        assertEquals("birds", OpenBankMatch.habitat("Gulls on the Harbor"))
    }
}
