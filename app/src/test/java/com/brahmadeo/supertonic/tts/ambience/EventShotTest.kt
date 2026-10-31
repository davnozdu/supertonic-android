package com.brahmadeo.supertonic.tts.ambience

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Звук события не должен зависеть от фоновой подложки: на улице, во дворе и между сценами фона нет, а дверь хлопнуть
 * всё равно обязана. Глушим только остановленное чтение. */
class EventShotTest {

    @Test
    fun anEventSoundsWithoutALocationBed() {
        // Фона нет вовсе: место не размечено (улица, двор) — событие всё равно звучит, пока идёт речь.
        assertTrue(EventShot.allowed(speech = true, bedPlaying = false, bedMaster = 0f))
    }

    @Test
    fun anEventSoundsWhileTheBedFadesOut() {
        assertTrue(EventShot.allowed(speech = true, bedPlaying = true, bedMaster = 0f))
    }

    @Test
    fun anEventIsDroppedWhenReadingStopped() {
        assertFalse(EventShot.allowed(speech = false, bedPlaying = false, bedMaster = 0f))
    }

    @Test
    fun aBedStillLetsAChapterSoundThroughBetweenSentences() {
        // Речь между предложениями молчит на миг, но фон играет — звук перехода главы не теряется.
        assertTrue(EventShot.allowed(speech = false, bedPlaying = true, bedMaster = 0.8f))
    }
}
