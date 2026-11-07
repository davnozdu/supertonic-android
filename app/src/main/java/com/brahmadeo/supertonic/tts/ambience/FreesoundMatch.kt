package com.brahmadeo.supertonic.tts.ambience

/** Require the recorded subject/action in title or tags, never in an unrelated description anecdote. */
internal object FreesoundMatch {
    private val aliases = mapOf(
        "phone" to "phone telephone", "ring" to "ring ringing ringtone", "car" to "car auto automobile vehicle",
        "horn" to "horn honk honking", "door" to "door doorway", "creak" to "creak creaking squeak squeaking",
        "open" to "open opening", "close" to "close closing shut", "slam" to "slam slamming bang",
        "wood" to "wood wooden timber", "metal" to "metal metallic iron steel", "plastic" to "plastic pvc",
        "footsteps" to "footstep footsteps steps walking walk", "running" to "run running jog jogging",
        "cry" to "cry crying sob sobbing", "baby" to "baby infant", "woman" to "woman female",
        "man" to "man male", "laugh" to "laugh laughter laughing", "clink" to "clink clinking tinkle",
        "glass" to "glass glasses", "break" to "break breaking broken smash shatter",
        "knock" to "knock knocking", "latch" to "latch bolt", "lock" to "lock locking unlock unlocking",
        "long" to "long sustained continuous", "short" to "short brief single", "far" to "far distant",
        "train" to "train locomotive railway railroad", "gunshot" to "gunshot gunfire shot",
        "cough" to "cough coughing", "sigh" to "sigh sighing", "page" to "page pages book",
        "turn" to "turn turning flip flipping", "water" to "water", "pour" to "pour pouring",
        "rain" to "rain rainfall raining", "wind" to "wind windy", "bird" to "bird birds birdsong")
    private fun stem(word: String): String = word.lowercase().removeSuffix("ing").removeSuffix("ed").removeSuffix("s").trimEnd('e')
    private fun tokens(text: String) = Regex("[a-z]+").findAll(text.lowercase()).map { stem(it.value) }.toSet()
    fun allowedLicense(url: String) = Regex("https?://creativecommons\\.org/(?:publicdomain/zero/1\\.0|licenses/by(?:-nc)?/(?:3\\.0|4\\.0))/?").matches(url)
    fun event(kind: String, title: String, tags: List<String>): Boolean {
        val text = "$title ${tags.joinToString(" ")}".lowercase()
        if (Regex("\\b(?:music|song|remix|synthesized|synthetic|cartoon|imitation)\\b").containsMatchIn(text)) return false
        val terms = kind.lowercase().split('_').filter { it.isNotBlank() && it !in setOf("on", "the", "a", "sound", "sfx") }
        if (terms.isEmpty()) return false
        val present = tokens(text)
        return terms.all { term -> tokens(aliases[term] ?: term).any { it in present } }
    }
}
