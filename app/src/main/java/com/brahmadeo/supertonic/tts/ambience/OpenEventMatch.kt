package com.brahmadeo.supertonic.tts.ambience

/** Audited corrections to v1 metadata. Exact kinds only: a similar word is not the same action. */
object OpenEventMatch {
    private val replacements = mapOf(
        "wine" to emptySet(), // Bottle clink is not pouring wine.
        "horn" to emptySet(), // Generic horns cannot stand in for the legacy hunting-horn event.
        "bird" to emptySet(), // A nestling calling is not wing flutter.
        "typewriter" to emptySet(), // Space key and carriage bell are not typing.
        "door_grind" to emptySet(), // A creaking doorknob is not a heavy door forced open.
        "box_open" to emptySet(), // Metal box is not the legacy wooden box.
        "coin" to emptySet(), // A spinning coin is not counting coins.
        "sneeze" to emptySet(), // Female sneezing is not the legacy male sneeze.
        "saw" to setOf("0016", "0559"), // Hand saw, never motorized saws.
        "clock" to setOf("0007", "1567"),
        "clock_tick" to setOf("0007", "1567"),
        "computer_beeps" to emptySet(), // Truck reversing alarms, despite "made on computer" in description.
        "fishing_reel" to emptySet(), // Plastic toy, not a working fishing reel.
        "flies" to emptySet(), // Cockchafer, not a fly.
        "pig" to emptySet(), // Human vocal imitation, not an animal recording.
        "pistol_shot" to setOf("0438"), // 2854 is a shotgun, despite its translated title.
        "rifle_shot" to setOf("0397"),
        "shotgun_shot" to setOf("2854"),
        "gunshot" to setOf("0397", "0437", "0438"),
        "gun_cock" to setOf("1986", "1992", "1993"), // Disarming is a different action.
        "children_play" to emptySet(), // Screaming remains available as scream_child.
        "door_slide" to setOf("0336"),
        "chair" to setOf("0666"), // Moving a chair, not sitting down or standing up.
        "paper" to setOf("1250"),
        "match_strike" to setOf("1271"),
        "electric_switch" to setOf("0052"),
        "sigh" to setOf("1404", "1405")
    )
    private val aliases = mapOf(
        "phone_ring" to "phone", "phone_ringing" to "phone", "telephone_ring" to "phone",
        "ring_phone" to "phone", "opening_door" to "door_open", "closing_door" to "door_close",
        "creaking_door" to "door_creak", "door_bang" to "door_slam", "page_flip" to "page_turn",
        "glass_clinking" to "glass_clink", "gun_shot" to "gunshot", "crying_baby" to "cry_baby",
        "crying_child" to "cry_child", "coughing" to "cough", "footsteps_on_wood" to "footsteps_wood"
    )
    fun matches(kind: String, id: String, catalogKinds: Set<String>): Boolean {
        val key = aliases[kind] ?: kind
        if (id.startsWith("fs-")) return key in catalogKinds // independently checked Freesound object/action metadata
        return replacements[key]?.let { id in it } ?: (key in catalogKinds)
    }
}
