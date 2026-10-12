package com.brahmadeo.supertonic.tts.foreign

/** The one «MyTTS Мультиязычный» voice, for readers that set a voice explicitly (Readest lists the voices of the
 * book's language and takes the first by name). It reads like a reader that only sets the language (Moon+): the voice
 * chosen in MyTTS (and the book's voices) for Russian, every other connected language through its own engine from
 * «Мультиязычная озвучка». Advertised under Russian, the language of the models; "MyTTS…" sorts before "ru-supertonic-…". */
object MultilingualVoice {
    const val NAME = "MyTTS Мультиязычный"
    fun matches(voiceName: String?) = voiceName == NAME
}
