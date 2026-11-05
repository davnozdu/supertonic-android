package com.brahmadeo.supertonic.tts.ambience

import org.junit.Assert.*
import org.junit.Test

class HumanReactionsTest {
    @Test fun allVocalReactionsAndTheirVariantsCanBeMuted() {
        for (kind in listOf("sigh", "cry", "weeping", "scream_woman", "scream_child", "laugh_soft", "sob_muffled",
                "breath_shaky", "crowd_gasp", "chuckle", "giggle", "sniffle", "yawn", "hiccup"))
            assertTrue("missing category: $kind", HumanReactions.contains(kind))
    }
    @Test fun objectsAndAnimalsKeepTheirSounds() {
        for (kind in listOf("door_slam", "footsteps_wood", "glass_break", "kettle_whistle", "wind_whistle", "steam_whistle",
                "horse_snort", "fox_scream", "owl_call", "hull_groan", "pig"))
            assertFalse("wrong category: $kind", HumanReactions.contains(kind))
    }
    @Test fun oldClipsAndUnknownVocalKindsUseTheirDescription() {
        assertTrue(HumanReactions.contains("", "a woman crying quietly"))
        assertTrue(HumanReactions.contains("human_gasp", ""))
        assertTrue(HumanReactions.contains("custom_cry", "a man sobbing in fear"))
        assertFalse(HumanReactions.contains("door_slam", "a man slamming a door"))
        assertFalse(HumanReactions.contains("custom_steps", "a man walking across a wooden floor"))
        assertFalse(HumanReactions.contains("", "a wooden hull groaning in heavy seas"))
        assertFalse(HumanReactions.contains("", "a fox screaming at night"))
    }
}
