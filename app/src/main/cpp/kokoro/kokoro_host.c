#include <espeak-ng/speak_lib.h>
int mytts_kokoro_init(const char *path) {
    return espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path, espeakINITIALIZE_DONT_EXIT) > 0 &&
           espeak_SetVoiceByName("ru") == EE_OK;
}
const char *mytts_kokoro_phonemes(const void **text) {
    return espeak_TextToPhonemes(text, espeakCHARS_UTF8, espeakPHONEMES_IPA | espeakPHONEMES_TIE | ('^' << 8));
}
