package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun betaVersionsAndFinalVersionsAreOrdered() {
        assertTrue(UpdateChecker.isNewer("v4.1.0-beta6", "4.1.0-beta5"))
        assertTrue(UpdateChecker.isNewer("v4.1.0", "4.1.0-beta6"))
        assertFalse(UpdateChecker.isNewer("v4.1.0-beta6", "4.1.0"))
        assertFalse(UpdateChecker.isNewer("v4.1.0-beta6", "4.1.0-beta6"))
    }

    @Test fun modelAssetTagsCannotBecomeAppUpdates() {
        assertFalse(UpdateChecker.isNewer("assets-v1", "4.1.0-beta5"))
        assertFalse(UpdateChecker.isNewer("russian-v1.1", "4.1.0-beta5"))
    }
}
