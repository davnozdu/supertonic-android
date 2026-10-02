package com.brahmadeo.supertonic.tts

import com.brahmadeo.supertonic.tts.utils.AccentDictionaryManager
import com.brahmadeo.supertonic.tts.tera.TeraTextPreparation
import org.junit.Assert.assertEquals
import org.junit.Test

class AccentDictionaryStressTest {
    @Test fun llmAndManualStressSurviveBothDictionaryStages() {
        val manager = AccentDictionaryManager
        val entries = manager.javaClass.getDeclaredField("entries").apply { isAccessible = true }
        val binary = manager.javaClass.getDeclaredField("binaryDict").apply { isAccessible = true }
        val savedEntries = entries.get(manager)
        val savedBinary = binary.get(manager)
        try {
            binary.set(manager, null)
            entries.set(manager, mapOf("окно" to "о́кно", "светло" to "све́тло"))
            val explicit = "окно́ светло́ +окно светл+о"
            assertEquals(explicit, manager.apply(explicit, "ru"))
            assertEquals("окн+о светл+о +окно светл+о", TeraTextPreparation.stress(manager.apply(explicit, "ru"),
                { " +wrong" }, emptyMap(), emptySet(), emptySet()))
            assertEquals("о́кно", manager.apply("окно", "ru"))
        } finally {
            entries.set(manager, savedEntries)
            binary.set(manager, savedBinary)
        }
    }
}
