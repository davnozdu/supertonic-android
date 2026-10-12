package com.brahmadeo.supertonic.tts.foreign

/** The «Мультиязычный» voice of the MyTTS engine, for readers that set a voice explicitly (Readest: one voice per
 * language of the book, the first by name by default). It reads like a reader that only sets the language (Moon+):
 * the voice chosen in MyTTS (and the book's voices) for Russian, every other language through the external engine
 * of «Мультиязычная озвучка». "ru-multilingual-MyTTS" sorts before the model voices "ru-supertonic-…"; its second
 * subtag is longer than 8 letters, so Locale.forLanguageTag reads it as plain "ru", like the model voices. */
object MultilingualVoice {
    private const val SUFFIX = "-multilingual-MyTTS"
    fun name(language: String) = language + SUFFIX
    fun matches(voiceName: String?) = voiceName != null && voiceName.endsWith(SUFFIX) && voiceName.length > SUFFIX.length
    fun language(voiceName: String) = voiceName.removeSuffix(SUFFIX)
}
