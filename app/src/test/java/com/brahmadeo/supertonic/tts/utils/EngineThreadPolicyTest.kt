package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.*
import org.junit.Test

class EngineThreadPolicyTest {
    @Test fun defaultsRespectSmallDevices() {
        for (cores in 1..16) for (model in listOf("teratts_v2","kokoro_ru_v2","silero_v5_5_ru","silero_cis_ru","shtorm_pocket_ru","standard")) {
            assertTrue(EngineThreadPolicy.recommended(model,cores) in 1..cores)
        }
        assertEquals(1,EngineThreadPolicy.maximum(0))
        assertEquals(16,EngineThreadPolicy.maximum(64))
    }
    @Test fun measuredAndConservativeDefaults() {
        assertEquals(2,EngineThreadPolicy.recommended("teratts_v2",8))
        assertEquals(4,EngineThreadPolicy.recommended("kokoro_ru_v2",8))
        for (model in listOf("silero_v5_5_ru","silero_cis_ru","shtorm_pocket_ru"))
            assertEquals(2,EngineThreadPolicy.recommended(model,8))
    }
    @Test fun userChoiceIsRetainedAndClamped() {
        assertEquals(2,EngineThreadPolicy.selected("teratts_v2",8,2))
        assertEquals(8,EngineThreadPolicy.selected("kokoro_ru_v2",8,99))
        assertEquals(1,EngineThreadPolicy.selected("kokoro_ru_v2",8,-1))
        assertEquals(4,EngineThreadPolicy.selected("kokoro_ru_v2",8,null))
    }
    @Test fun choicesAreIndependentForEachModel() {
        val models = listOf("teratts_v2","kokoro_ru_v2","silero_v5_5_ru","silero_cis_ru","shtorm_pocket_ru","standard")
        assertEquals(models.size, models.map(EngineThreadPolicy::key).toSet().size)
        val saved = mapOf(EngineThreadPolicy.key(models[0]) to 2)
        assertEquals(2,EngineThreadPolicy.selected(models[0],8,saved[EngineThreadPolicy.key(models[0])]))
        assertEquals(4,EngineThreadPolicy.selected(models[1],8,saved[EngineThreadPolicy.key(models[1])]))
    }
}
