package com.brahmadeo.supertonic.tts.kokoro

internal object KokoroPhonemizer {
    init { System.loadLibrary("kokoro_g2p") }
    external fun initialize(dataPath: String): Boolean
    external fun phonemes(marked: String): String
}
