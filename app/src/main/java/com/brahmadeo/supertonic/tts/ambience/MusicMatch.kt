package com.brahmadeo.supertonic.tts.ambience

/** Music retrieval is separate from sound effects. Unknown mood/instruments are never inferred from poetic titles. */
internal object MusicMatch {
    private val moods = mapOf(
        "calm" to setOf("calm", "peaceful", "serene", "soothing", "relaxed", "relaxing"),
        "reflective" to setOf("reflective", "contemplative", "introspective"),
        "sad" to setOf("sad", "melancholic", "melancholy", "sorrowful"),
        "nostalgic" to setOf("nostalgic", "nostalgia"),
        "tense" to setOf("tense", "tension", "suspense", "suspenseful", "uneasy", "unsettled"),
        "dark" to setOf("dark", "ominous", "sinister", "menacing"),
        "mysterious" to setOf("mysterious", "mystery", "enigmatic"),
        "happy" to setOf("happy", "joyful", "cheerful", "uplifting"))
    private val instruments = mapOf(
        "piano" to setOf("piano"), "cello" to setOf("cello"), "violin" to setOf("violin"),
        "strings" to setOf("strings", "string"), "guitar" to setOf("guitar"), "flute" to setOf("flute"),
        "synth" to setOf("synth", "synthesizer", "synthesizers"), "drone" to setOf("drone", "drones"),
        "percussion" to setOf("percussion", "drums", "drum", "beats", "beat"))
    private val energy = mapOf("soft" to setOf("soft", "quiet", "gentle", "restrained"),
        "strong" to setOf("intense", "intensified", "aggressive", "powerful", "energetic"))
    private val tempo = mapOf("slow" to setOf("slow", "unhurried", "adagio"),
        "fast" to setOf("fast", "rapid", "allegro"))
    private val styles = mapOf("ambient" to setOf("ambient"), "classical" to setOf("classical"),
        "jazz" to setOf("jazz"), "waltz" to setOf("waltz"), "march" to setOf("march"),
        "orchestral" to setOf("orchestral", "orchestra"))
    private fun intensity(text: String) = when {
        "low intensity" in text.lowercase() -> "low"
        Regex("\\b(?:intensified|high intensity)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "high"
        else -> "normal"
    }
    private val vocals = Regex("\\b(?:vocals?|vocalists?|singing|singers?|choirs?|speech|spoken|voices?|lyrics|rap)\\b", RegexOption.IGNORE_CASE)
    private val vocalNegation = Regex("\\b(?:no|without)\\s+(?:any\\s+)?(?:vocals?|vocalists?|singing|singers?|choirs?|speech|spoken|voices?|lyrics|rap)\\b", RegexOption.IGNORE_CASE)
    private val percussionNegation = Regex("\\b(?:no|without)\\s+(?:any\\s+)?(?:percussion|drums?|beats?)\\b", RegexOption.IGNORE_CASE)
    private val keySignature = Regex("\\b([a-g](?:#|b)?)\\s+(minor|major)\\b", RegexOption.IGNORE_CASE)
    private val bpm = Regex("\\b([3-9][0-9]|1[0-9]{2})\\s*bpm\\b", RegexOption.IGNORE_CASE)
    private fun words(text: String) = Regex("[a-z]+").findAll(text.lowercase()).map { it.value }.toSet()
    private fun groups(text: String, dictionary: Map<String, Set<String>>) = words(text).let { w -> dictionary.filterValues { a -> a.any { it in w } }.keys }
    fun canonical(prompt: String) = prompt.lowercase().replace("high-fidelity stereo studio recording, crisp clear detailed highs, wide stereo", "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()
    fun exact(request: String, candidate: String) = canonical(request).isNotBlank() && canonical(request) == canonical(candidate)
    fun query(prompt: String): String? {
        val mood = groups(prompt, moods).firstOrNull() ?: return null
        val instrument = groups(percussionNegation.replace(prompt, " "), instruments).firstOrNull() ?: return null
        return "$mood $instrument instrumental music"
    }
    /** Source descriptors must explicitly establish instrumental music, mood and instrumentation. */
    fun matches(request: String, title: String, description: String, tags: List<String>): Boolean {
        val source = "$title $description ${tags.joinToString(" ")}".lowercase()
        val cleanSource = vocalNegation.replace(source, " ")
        if (vocals.containsMatchIn(cleanSource)) return false
        if (!Regex("\\b(?:instrumental|music|soundtrack|composition|score)\\b").containsMatchIn(title.lowercase()+" "+tags.joinToString(" ").lowercase())) return false
        if (!("instrumental" in words(source) || vocalNegation.containsMatchIn(source))) return false
        val requestedMoods = groups(request, moods)
        if (requestedMoods.isEmpty() || requestedMoods != groups(source, moods)) return false
        val requestedInstruments = groups(percussionNegation.replace(request, " "), instruments)
        if (requestedInstruments.isEmpty() || requestedInstruments != groups(percussionNegation.replace(source, " "), instruments)) return false
        if (percussionNegation.containsMatchIn(request) && !percussionNegation.containsMatchIn(source)) return false
        val requestedEnergy = groups(request, energy)
        if (requestedEnergy.isNotEmpty() && requestedEnergy != groups(source, energy)) return false
        val requestedTempo = groups(request, tempo)
        if (requestedTempo.isNotEmpty() && requestedTempo != groups(source, tempo)) return false
        val rate = bpm.find(request)?.groupValues?.get(1)?.toIntOrNull()
        if (rate != null && bpm.find(source)?.groupValues?.get(1)?.toIntOrNull()?.let { kotlin.math.abs(it-rate) <= 3 } != true) return false
        val requestedKey = keySignature.find(request)?.value?.lowercase()
        if (requestedKey != null && requestedKey != keySignature.find(source)?.value?.lowercase()) return false
        val requestedMode = words(request).intersect(setOf("minor", "major"))
        if (requestedMode.isNotEmpty() && requestedMode != words(source).intersect(setOf("minor", "major"))) return false
        val requestedStyle = groups(request, styles)
        if (requestedStyle.isNotEmpty() && requestedStyle != groups(source, styles)) return false
        if ("sparse" in words(request) && "sparse" !in words(source)) return false
        // Intensities are part of the request, not decorative words.
        if (intensity(request) != intensity(source)) return false
        if ("strong" in requestedEnergy && "strong" !in groups(source, energy)) return false
        return true
    }
}
