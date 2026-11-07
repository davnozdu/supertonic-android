package com.brahmadeo.supertonic.tts.ambience

/** Require the recorded subject/action in title or tags, never in an unrelated description anecdote. */
internal object FreesoundMatch {
    private val aliases = mapOf(
        "phone" to "phone telephone", "ring" to "ring ringing ringtone", "car" to "car auto automobile vehicle",
        "horn" to "horn honk honking", "door" to "door doorway", "creak" to "creak creaking squeak squeaking",
        "open" to "open opening", "close" to "close closing shut", "slam" to "slam slamming bang",
        "wood" to "wood wooden timber", "metal" to "metal metallic iron steel", "plastic" to "plastic pvc",
        "footsteps" to "footstep footsteps steps walking walk", "running" to "run running jog jogging",
        "baby" to "baby infant", "woman" to "woman female",
        "man" to "man male men", "laugh" to "laugh laughter laughing", "clink" to "clink clinking tinkle",
        "glass" to "glass glasses", "break" to "break breaking broken smash shatter",
        "knock" to "knock knocking", "latch" to "latch bolt", "lock" to "lock locking unlock unlocking",
        "long" to "long sustained continuous", "short" to "short brief single", "far" to "far distant",
        "train" to "train locomotive railway railroad", "gunshot" to "gunshot gunfire shot",
        "cough" to "cough coughing", "sigh" to "sigh sighing", "page" to "page pages book",
        "turn" to "turn turning flip flipping", "water" to "water", "pour" to "pour pouring",
        "rain" to "rain rainfall raining", "wind" to "wind windy", "bird" to "bird birds birdsong", "human" to "human person man men woman female male child baby",
        "clank" to "clank clanking clang clanging", "armor" to "armor armour",
        "flyby" to "flyby flying flight", "arrow" to "arrow archery",
        "impact" to "impact hit hitting strike", "count" to "count counting",
        "set" to "set setting placed placing put", "down" to "down table desk floor",
        "flutter" to "flutter fluttering wings flapping", "low" to "lowing moo mooing",
        "start" to "start starting ignition", "cry" to "cry crying sob sobbing",
        "humming" to "hum humming", "typing" to "type typing",
        "draw" to "draw drawing writing", "clatter" to "clatter clattering rattle rattling")
    private val specificKinds = mapOf(
        "horn" to "hunting_horn", "wine" to "wine_pour", "bird" to "bird_flutter",
        "phone" to "phone_ring", "typewriter" to "typewriter_typing", "coin" to "coin_count",
        "box_open" to "wood_box_open", "sneeze" to "man_sneeze", "car" to "car_pass",
        "animal" to "cow_low", "armor" to "armor_clank", "arrow" to "arrow_flyby",
        "axe" to "axe_chop_wood", "bag" to "leather_bag", "bell_single" to "church_bell_single",
        "blotting" to "blotting_paper", "book_slam" to "book_close", "boots_floor" to "boots_wood",
        "bottle" to "bottle_set_down", "brake_squeal" to "train_brake_squeal",
        "cart" to "wood_cart_wheels", "cattle" to "cow_low", "chair" to "chair_scrape",
        "charcoal" to "charcoal_draw", "chest" to "wood_chest_lid", "child" to "child_calling",
        "choir" to "church_choir", "clockwork" to "clockwork_mechanism",
        "coach_door" to "horse_carriage_door", "crack" to "wood_break", "crowd" to "crowd_murmur",
        "cry" to "human_cry", "cup" to "cup_set", "dishes" to "dishes_clatter",
        "dog" to "dog_bark", "drum" to "military_drum", "ducks" to "duck_quack",
        "engine" to "engine_start", "fight" to "human_scuffle", "fire" to "fire_crackle",
        "flush" to "toilet_flush", "gasp" to "human_gasp", "gate" to "wood_gate",
        "groan" to "human_groan", "growl" to "human_growl", "hum" to "woman_humming",
        "ink" to "ink_dip", "lamp" to "oil_lamp_chimney", "lamp_glass" to "oil_lamp_glass")
    private fun stem(word: String): String = word.lowercase().removeSuffix("ing").removeSuffix("ed").removeSuffix("s").trimEnd('e')
    private fun tokens(text: String) = Regex("[a-z]+").findAll(text.lowercase()).map { stem(it.value) }.toSet()
    fun allowedLicense(url: String) = Regex("https?://creativecommons\\.org/(?:publicdomain/zero/1\\.0|licenses/by(?:-nc)?/(?:3\\.0|4\\.0))/?").matches(url)
    fun event(kind: String, title: String, tags: List<String>): Boolean {
        val text = "$title ${tags.joinToString(" ")}".lowercase()
        if (Regex("\\b(?:music|song|remix|synthesized|synthetic|cartoon|imitation)\\b").containsMatchIn(text)) return false
        val terms = (specificKinds[kind.lowercase()] ?: kind.lowercase()).split('_').filter { it.isNotBlank() && it !in setOf("on", "the", "a", "sound", "sfx") }
        if (terms.isEmpty()) return false
        val present = tokens(title) // Incidental tags do not establish the main recorded event.
        return terms.all { term -> tokens(aliases[term] ?: term).any { it in present } }
    }
}
