package com.brahmadeo.supertonic.tts.ambience

import org.junit.Assert.*
import org.junit.Test

class OpenBankMatchTest {
    private val forest = OpenBankMatch.Scene("forest", setOf("forest", "birds"), setOf("forest", "birds", "morning"))
    private val rain = OpenBankMatch.Scene("rainforest", setOf("forest", "rain"), setOf("forest", "rain"))
    private val street = OpenBankMatch.Scene("street", setOf("street"), setOf("street", "traffic", "cars"))

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
        val crowd = OpenBankMatch.Scene("cafe", setOf("restaurant"), setOf("voices", "conversation"))
        assertTrue(OpenBankMatch.scenes("cafe without voices", listOf(crowd)).isEmpty())
    }
    @Test fun equivalentVariantsAreNotRejectedAsAmbiguous() {
        assertEquals(2, OpenBankMatch.scenes("quiet forest", listOf(forest, forest.copy(id = "forest2"))).size)
    }
}
