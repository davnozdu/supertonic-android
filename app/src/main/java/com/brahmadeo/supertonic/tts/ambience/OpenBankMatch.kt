package com.brahmadeo.supertonic.tts.ambience

/** Conservative, Android-free retrieval. An unsuitable recording is worse than generation. */
object OpenBankMatch {
    data class Scene(val id: String, val concepts: Set<String>, val tags: Set<String>,
                     val title: String = "", val seconds: Double = 0.0)
    private val groups = linkedMapOf(
        "forest" to setOf("forest", "woodland", "woods"),
        "river" to setOf("river", "stream", "brook", "creek"),
        "sea" to setOf("sea", "ocean", "beach", "shore", "waves"),
        "street" to setOf("street", "urban", "city", "crossroads", "downtown", "boulevard"),
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
        "rain" to setOf("rain", "rainfall", "raining", "rainy"),
        "wind" to setOf("wind", "windy"), "birds" to setOf("bird", "birds", "birdsong"),
        "crowd" to setOf("crowd", "crowds", "people", "chatter")
    )
    fun concepts(text: String): Set<String> {
        val tokens = BankText.tokens(text)
        return groups.filterValues { words -> words.any { it in tokens } }.keys
    }

    /** Only the title identifies the main subject. Description tags describe incidental sounds too. */
    fun habitat(title: String): String? {
        val t = BankText.tokens(title)
        fun has(vararg words: String) = words.any { it in t }
        return when {
            has("passing", "passage", "starts", "departs", "honking", "applause", "halloween", "turbine") -> null
            has("apartment") || "inside a house" in title.lowercase() -> "apartment"
            has("forest", "forests") -> "forest"
            has("stream", "river", "dam", "waterfall", "cascade") -> "river"
            has("sea", "ocean", "beach", "waves") -> "sea"
            has("market", "flea", "capuchins") -> "market"
            has("restaurant", "bar", "coffee", "cafe") -> "restaurant"
            has("cathedral", "church", "mass", "basilica") -> "church"
            has("station", "gare") -> "station"
            has("metro", "tram", "eurostar", "train", "locomotive") -> "train"
            has("highway", "motorway") || "ring road" in title.lowercase() -> "road"
            has("harbor", "harbour") && !has("gulls", "birds", "swallows") -> "harbor"
            has("garden", "park", "undergrowth") -> "park"
            has("office") -> "office"
            has("pool") -> "pool"
            has("barn") && !has("swallows") -> "farm"
            has("countryside", "campaign", "field", "fields", "cornfield") -> "farm"
            has("rain", "thunder") -> "rain"
            has("wind", "whistling") -> "wind"
            has("birds", "bird", "swallows", "crows", "starlings", "gulls") -> "birds"
            has("crowd", "walla", "talks", "discussions") -> "crowd"
            has("street", "crossroads", "crossing", "downtown", "suburb", "pedestrian") ||
                "city center" in title.lowercase() || "paris by night" in title.lowercase() -> "street"
            else -> null
        }
    }

    /** A bed is a sustained location recording, never a passing vehicle or an incidental tag match. */
    fun scenes(prompt: String, candidates: List<Scene>): List<String> {
        // The suffix contains separate events. It must not change the location being searched.
        val location = prompt.substringBefore('|')
        val negative = Regex("\\b(?:no|without)\\s+(?:(?:human|any|audible|background|nearby|distant)\\s+)?(rain|wind|traffic|birds|birdsong|voices|speech|talking|music|cars|crowd|people)\\b", RegexOption.IGNORE_CASE)
        val forbidden = negative.findAll(location).map { it.groupValues[1].lowercase() }.toSet()
        val positive = negative.replace(location, " ")
        val words = BankText.tokens(positive)
        val wanted = concepts(positive).toMutableSet()
        val interior = words.any { it in setOf("interior", "indoors", "indoor", "penthouse", "apartment", "flat") } &&
            words.none { it in setOf("ledge", "balcony", "rooftop", "outside", "outdoors") }
        val elevatedCity = words.any { it in setOf("penthouse", "skyscraper", "ledge", "balcony", "rooftop") }
        if (elevatedCity) wanted.add("street")
        val primary = when {
            interior && ("street" in wanted || "apartment" in wanted) -> "apartment"
            elevatedCity -> "street"
            else -> (wanted - setOf("rain", "wind", "birds", "crowd")).firstOrNull() ?: wanted.firstOrNull()
        } ?: return emptyList()
        val openWindow = "open" in words && "window" in words
        val historical = words.any { it in setOf("medieval", "carriage", "horses", "nineteenth", "ancient") }
        val night = words.any { it in setOf("night", "midnight", "nocturnal") }
        val day = words.any { it in setOf("morning", "dawn", "daytime") }
        val noVoices = forbidden.any { it in setOf("voices", "speech", "talking", "crowd", "people") } || Regex("(?:no|without)\\s+(?:human\\s+)?(?:voices|speech|talking)", RegexOption.IGNORE_CASE).containsMatchIn(location)
        val noMusic = "music" in forbidden || Regex("(?:no|without)\\s+music", RegexOption.IGNORE_CASE).containsMatchIn(location)
        val ranked = candidates.asSequence().filter { c ->
            val tags = c.tags + BankText.tokens(c.title)
            val rainy = tags.any { it in setOf("rain", "rainy", "rainfall", "raining", "thunder", "storm") }
            val disruptiveUrban = tags.any { it in setOf("frogs", "frog", "toad", "insects", "amphibian", "construction",
                "roadworks", "garbage", "parade", "procession", "victory", "cheerful", "bandas", "batucada",
                "musicians", "musician", "celebration", "dancers", "dancer") }
            val titleWords = BankText.tokens(c.title)
            val outdoor = words.any { it in setOf("outside", "outdoor", "outdoors", "openair") }
            val traffic = tags.any { it in setOf("traffic", "car", "cars", "motor", "truck", "bus", "road") }
            val birdCalls = tags.any { it in setOf("bird", "birds", "birdsong", "chirping", "gulls", "crows") }
            c.seconds >= 30.0 && habitat(c.title) == primary &&
                (forbidden.none { it in setOf("traffic", "cars") } || !traffic) &&
                (forbidden.none { it in setOf("birds", "birdsong") } || !birdCalls) &&
                ("wind" !in forbidden || tags.none { it in setOf("wind", "windy") }) &&
                (!outdoor || tags.none { it in setOf("indoor", "indoors", "covered", "interior") }) &&
                (primary != "forest" || "river" in wanted || titleWords.none { it in setOf("river", "stream", "waterfall") }) &&
                (primary != "forest" || words.any { it in setOf("deer", "sika", "rut") } || "rut" !in titleWords) &&
                (!historical || primary != "street" || tags.any { it in setOf("horse", "horses", "carriage") }) &&
                (primary != "apartment" || !openWindow || "closed" !in tags) &&
                (rainy == ("rain" in wanted)) &&
                (primary != "street" || !disruptiveUrban) &&
                (primary != "street" || !(elevatedCity || "traffic" in words) ||
                    tags.any { it in setOf("traffic", "road", "cars", "car", "boulevard") }) &&
                (!historical || tags.none { it in setOf("car", "cars", "truck", "engine", "motor", "traffic", "airplane") }) &&
                (!night || (tags.none { it in setOf("morning", "dawn", "daytime", "day") } &&
                    (tags.none { it in setOf("birds", "bird", "birdsong", "chirping") } || tags.any { it in setOf("night", "nocturnal") }))) &&
                (!day || "night" !in tags) &&
                (!noVoices || tags.none { it in setOf("voices", "speech", "conversation", "talking", "crowd") }) &&
                (!noMusic || tags.none { it in setOf("music", "musician", "musicians", "singing", "organ", "choir") })
        }.map { c ->
            // Incidental tags may rank an already valid habitat, never establish that habitat.
            c.id to (c.tags.count { it in words } +
                if (primary == "apartment" && !openWindow && "closed" in BankText.tokens(c.title)) 10 else 0)
        }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }).toList()
        val best = ranked.firstOrNull()?.second ?: return emptyList()
        return ranked.filter { it.second == best }.take(12).map { it.first }
    }
}
