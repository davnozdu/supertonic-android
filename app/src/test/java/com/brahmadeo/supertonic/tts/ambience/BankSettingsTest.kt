package com.brahmadeo.supertonic.tts.ambience

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class BankSettingsTest {
    private fun prefs(vararg flags: Pair<String, Boolean>): SharedPreferences {
        val map = flags.toMap()
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getBoolean" -> map[args!![0]] ?: args[1]
                "getAll" -> map
                else -> null
            }
        } as SharedPreferences
    }
    @Test fun newBanksRequireExplicitEnabling() {
        val p = prefs()
        assertFalse(AmbienceSettings.realEvents(p))
        assertFalse(AmbienceSettings.realScenes(p))
        assertTrue(AmbienceSettings.soundBank(p)) // Existing user preference is preserved.
    }
    @Test fun legacyCanBeDisabledWhileBothNewBanksStayEnabled() {
        val p = prefs(AmbienceSettings.SOUND_BANK to false, AmbienceSettings.REAL_EVENTS to true, AmbienceSettings.REAL_SCENES to true)
        assertFalse(AmbienceSettings.soundBank(p))
        assertTrue(AmbienceSettings.realEvents(p)); assertTrue(AmbienceSettings.realScenes(p))
    }
    @Test fun eventBankChangeInvalidatesOnlyEventRecipe() {
        val a = prefs(); val b = prefs(AmbienceSettings.REAL_EVENTS to true)
        assertNotEquals(AmbienceSettings.bankRecipe(a, true), AmbienceSettings.bankRecipe(b, true))
        assertEquals(AmbienceSettings.bankRecipe(a, false), AmbienceSettings.bankRecipe(b, false))
    }
    @Test fun sceneBankChangeInvalidatesOnlySceneRecipe() {
        val a = prefs(); val b = prefs(AmbienceSettings.REAL_SCENES to true)
        assertNotEquals(AmbienceSettings.bankRecipe(a, false), AmbienceSettings.bankRecipe(b, false))
        assertEquals(AmbienceSettings.bankRecipe(a, true), AmbienceSettings.bankRecipe(b, true))
    }
}
