package com.brahmadeo.supertonic.tts.llm

/** Correlate pipeline stages without logging book contents. */
object SpeechTextTrace {
    fun fingerprint(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).take(12)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
