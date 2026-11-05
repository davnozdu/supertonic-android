package com.brahmadeo.supertonic.tts.ambience

import com.brahmadeo.supertonic.tts.books.EventKinds

/** Vocal reactions among event clips, not footsteps, impacts, animals or a location's mixed ambience. */
object HumanReactions {
    private val kinds = setOf(
        "laughter", "scream", "cry", "cough", "sigh", "child", "baby", "breath", "groan", "sneeze", "sobbing", "whisper",
        "orders_shout", "gulp", "laughter_woman", "laughter_child", "laughter_far", "scream_far", "shout_far", "breathing_calm",
        "street_crier", "crowd_panic", "argue", "baby_laugh", "breath_heavy", "breath_shaky", "children_play", "clear_throat",
        "crowd_boo", "crowd_cheer", "crowd_gasp", "cry_child", "crying_woman_far", "gasp", "groan_pain", "laugh_bitter",
        "laugh_loud", "laugh_soft", "moan", "murmur", "mutter_angry", "ovation", "pray", "scream_man", "scream_woman",
        "shout", "sigh_deep", "sigh_relief", "snore", "snort", "weeping", "whisper_female", "whisper_male", "whistle_human",
        "cabbie_shout", "newsboy", "cursing", "laugh_hysterical", "sob_muffled", "scream_child", "sigh_tired", "moan_relief",
        "prayer_murmur", "harbour_crowd", "market_trader", "street_children", "whisper_close", "cry_baby", "scream_pain",
        "groan_sleep", "battle_cry", "audience_laugh", "sniffle", "chuckle", "giggle", "yawn", "hiccup", "spit", "spittoon",
        "applause", "clap_single", "panting", "burp", "nose_blow", "teeth_grind"
    )
    private val vocalKind = Regex("^(?:laugh(?:ter)?|scream|shout|cry(?:ing)?|sigh|sob(?:bing)?|gasp|groan|moan|cough|sneeze|whisper|weeping|breath(?:ing)?|snore|snort)(?:_|$)")
    private val vocalDescription = Regex("(?iu)\\b(?:sigh\\w*|laugh\\w*|scream\\w*|shout\\w*|cry(?:ing)?|weeping|sob\\w*|gasp\\w*|groan\\w*|moan\\w*|cough\\w*|sneez\\w*|whisper\\w*|snor\\w*|breath\\w*|вздох\\p{L}*|сме[хя]\\p{L}*|плач\\p{L}*|крич\\p{L}*|крик\\p{L}*|рыда\\p{L}*|всхлип\\p{L}*|стон\\p{L}*|каш\\p{L}*|чих\\p{L}*|ш[её]пот\\p{L}*|шепч\\p{L}*)")
    private val human = Regex("(?iu)\\b(?:human|man|woman|people|person|child|baby|someone|crowd|человек\\p{L}*|мужчин\\p{L}*|женщин\\p{L}*|реб[её]нок|реб[её]нка|дет\\p{L}*|младен\\p{L}*)\\b")

    fun contains(kind: String, sound: String = ""): Boolean {
        val key = kind.lowercase().removePrefix("k_")
        if (key in kinds) return true
        // Known non-vocal sounds can mention a human or a whistle in their description. Preserve their category.
        if (EventKinds.proper(key)) return false
        if (vocalKind.containsMatchIn(key) || key.startsWith("human_")) return true
        if (key.isEmpty()) EventKinds.ALL.firstOrNull { it.sound.equals(sound, true) || it.hint.equals(sound, true) }
            ?.let { return it.key in kinds }
        return vocalDescription.containsMatchIn(sound) && human.containsMatchIn(sound)
    }
}
