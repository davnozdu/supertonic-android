package com.brahmadeo.supertonic.tts.foreign

/** Voices for readers that set a voice explicitly (Readest, KOReader's audiobook plugin): they list the voices of the
 * book's language and pick one (Readest takes the first by name). Advertised under Russian for every Russian model,
 * with or without other languages: "MyTTS…" sorts before "ru-supertonic-…", «Мультиголос» before «Один голос».
 * - [NAME] reads like a reader that only sets the language (Moon+): the voice chosen in MyTTS, the book's and the
 *   characters' voices; other languages connected in «Мультиязычная озвучка» through their engines.
 * - [SINGLE] the voice chosen in MyTTS alone, no role voices, for readers whose user wants one narrator.
 * [LEGACY] is the name of 5.0.4-beta14…5.0.5-beta1, still accepted as [NAME]. */
object MultilingualVoice {
    const val NAME = "MyTTS Мультиголос"
    const val SINGLE = "MyTTS Один голос"
    const val LEGACY = "MyTTS Мультиязычный"
    fun matches(voiceName: String?) = voiceName == NAME || voiceName == SINGLE || voiceName == LEGACY
    fun single(voiceName: String?) = voiceName == SINGLE
}
