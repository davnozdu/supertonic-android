package com.brahmadeo.supertonic.tts.books.prepare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Шаги по названной поверхности и машина: нужны и поверхность, и движение в том же абзаце. */
class MovementSoundsTest {

    private fun words(text: String) = text.split(" ")

    @Test
    fun stepsOnANamedSurfaceBecomeAnEvent() {
        val w = words("Она прошла по гравию к машине")
        val at = MovementSounds.surfaceIndex(w)!!
        assertEquals("footsteps_gravel", MovementSounds.surfaceKind(w, at))
    }

    @Test
    fun heelsOnHardGroundClick() {
        val w = words("Женщина в каблуках шла по тротуару")
        val at = MovementSounds.surfaceIndex(w)!!
        assertEquals("heel_walk_stone", MovementSounds.surfaceKind(w, at))
    }

    @Test
    fun floorboardsAndStairsAreTheirOwnKinds() {
        val floor = words("Он шёл по половицам")
        assertEquals("footsteps_wood", MovementSounds.surfaceKind(floor, MovementSounds.surfaceIndex(floor)!!))
        val stairs = words("Она поднималась по лестнице")
        assertEquals("footsteps_stairs", MovementSounds.surfaceKind(stairs, MovementSounds.surfaceIndex(stairs)!!))
    }

    @Test
    fun aSurfaceWithoutMotionIsNotASound() {
        assertNull(MovementSounds.surfaceIndex(words("Книга лежала на камне")))
    }

    @Test
    fun carEngineAndDoor() {
        assertEquals("car_start", MovementSounds.carIndex(words("Она завела мотор и отъехала"))!!.second)
        assertEquals("car_door_slam", MovementSounds.carIndex(words("Он хлопнул дверцей машины"))!!.second)
        assertNull(MovementSounds.carIndex(words("Машина стояла у дома")))
    }
}
