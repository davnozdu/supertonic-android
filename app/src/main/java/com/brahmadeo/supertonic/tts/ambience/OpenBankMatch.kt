package com.brahmadeo.supertonic.tts.ambience

/** Conservative, Android-free retrieval. An unsuitable recording is worse than generation. */
object OpenBankMatch {
    data class Scene(val id: String, val concepts: Set<String>, val tags: Set<String>)
    private val groups = linkedMapOf(
        "forest" to setOf("forest", "woodland", "woods"),
        "river" to setOf("river", "stream", "brook", "creek"),
        "sea" to setOf("sea", "ocean", "beach", "shore", "waves"),
        "street" to setOf("street", "urban", "city", "crossroads"),
        "road" to setOf("road", "highway", "motorway"),
        "market" to setOf("market", "marketplace", "bazaar"),
        "restaurant" to setOf("restaurant", "cafe", "cafeteria", "pub", "bar", "tavern"),
        "church" to setOf("church", "cathedral", "chapel"),
        "station" to setOf("station", "railway", "platform"),
        "train" to setOf("train", "subway", "metro"),
        "airport" to setOf("airport", "airfield"),
        "harbor" to setOf("harbor", "harbour", "port", "dock"),
        "office" to setOf("office"), "apartment" to setOf("apartment", "flat"),
        "kitchen" to setOf("kitchen"), "pool" to setOf("pool", "swimming"),
        "farm" to setOf("farm", "barn", "cows", "countryside", "rural", "field"),
        "park" to setOf("park", "garden"), "cave" to setOf("cave", "cavern"),
        "rain" to setOf("rain", "rainfall", "raining"),
        "wind" to setOf("wind", "windy"), "birds" to setOf("bird", "birds", "birdsong"),
        "crowd" to setOf("crowd", "crowds", "people", "chatter")
    )
    fun concepts(text: String): Set<String> {
        val tokens = BankText.tokens(text)
        return groups.filterValues { words -> words.any { it in tokens } }.keys
    }

    /** Weather and time conflicts are rejected; several equivalent takes are allowed. */
    fun scenes(prompt: String, candidates: List<Scene>): List<String> {
        val words = BankText.tokens(prompt)
        val wanted = concepts(prompt)
        if (wanted.isEmpty()) return emptyList()
        val habitats = wanted - setOf("rain", "wind", "birds", "crowd")
        val primary = habitats.firstOrNull() ?: wanted.first()
        val historical = words.any { it in setOf("medieval", "carriage", "horses", "nineteenth", "ancient") }
        val night = words.any { it in setOf("night", "midnight", "nocturnal") }
        val day = words.any { it in setOf("morning", "dawn", "daytime") }
        val noVoices = Regex("(?:no|without)\\s+(?:human\\s+)?(?:voices|speech|talking)", RegexOption.IGNORE_CASE).containsMatchIn(prompt)
        val noMusic = Regex("(?:no|without)\\s+music", RegexOption.IGNORE_CASE).containsMatchIn(prompt)
        return candidates.asSequence().filter { c ->
            primary in c.concepts &&
                ("rain" !in wanted || "rain" in c.concepts) &&
                ("rain" in wanted || "rain" !in c.concepts) &&
                (!historical || c.tags.none { it in setOf("car", "cars", "truck", "engine", "motor", "traffic", "airplane") }) &&
                (!night || c.tags.none { it in setOf("morning", "dawn", "daytime") }) &&
                (!day || "night" !in c.tags) &&
                (!noVoices || c.tags.none { it in setOf("voices", "speech", "conversation", "talking", "crowd") }) &&
                (!noMusic || c.tags.none { it in setOf("music", "singing", "organ", "choir") })
        }.map { c -> c.id to (c.concepts.count { it in wanted } * 10 + c.tags.count { it in words }) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(12).map { it.first }.toList()
    }
}
