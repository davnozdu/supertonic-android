package com.brahmadeo.supertonic.tts.utils

/** Reject callbacks from the previous reading while a replacement is being connected. */
class PlaybackContentGate {
    private var expected: String? = null
    fun expect(text: String) { expected = fingerprint(text) }
    fun resume() { expected = null }
    fun accept(callbackId: String, storedText: String, total: Int): Boolean {
        if (callbackId.isBlank() || callbackId != fingerprint(storedText)) return false
        expected?.let {
            if (total <= 0 || callbackId != it) return false
            expected = null
        }
        return true
    }
    fun awaitingReplacement() = expected != null
    companion object {
        fun fingerprint(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
