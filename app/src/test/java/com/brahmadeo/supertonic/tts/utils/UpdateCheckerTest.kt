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

    @Test fun dottedBetaRevisionsCompareEveryNumericPart() {
        assertTrue(UpdateChecker.isNewer("v5.0.0-beta4.10","5.0.0-beta4.9.2"))
        assertTrue(UpdateChecker.isNewer("v5.0.0-beta4.10.1","5.0.0-beta4.10"))
        assertTrue(UpdateChecker.isNewer("v5.0.0-beta4.9.2","5.0.0-beta4.9"))
        assertFalse(UpdateChecker.isNewer("v5.0.0-beta4.9.2","5.0.0-beta4.10"))
        assertFalse(UpdateChecker.isNewer("v5.0.0-beta4.10","5.0.0-beta4.10.1"))
        assertFalse(UpdateChecker.isNewer("v5.0.0-beta4.10.0","5.0.0-beta4.10"))
        assertTrue(UpdateChecker.isNewer("v5.0.0","5.0.0-beta4.10.1"))
    }
}
