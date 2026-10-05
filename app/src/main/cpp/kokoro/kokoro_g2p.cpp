#include <jni.h>
#include <espeak-ng/speak_lib.h>
#include <mutex>
#include <string>

static std::mutex guard;
static bool ready = false;
static std::string dataPath;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_brahmadeo_supertonic_tts_kokoro_KokoroPhonemizer_initialize(JNIEnv *env, jobject, jstring path) {
    std::lock_guard<std::mutex> lock(guard);
    const char *bytes = env->GetStringUTFChars(path, nullptr);
    if (!bytes) return false;
    std::string next(bytes);
    env->ReleaseStringUTFChars(path, bytes);
    if (ready && dataPath == next) return true;
    if (ready) espeak_Terminate();
    ready = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, next.c_str(), espeakINITIALIZE_DONT_EXIT) > 0;
    if (ready) ready = espeak_SetVoiceByName("ru") == EE_OK;
    dataPath = next;
    return ready;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_brahmadeo_supertonic_tts_kokoro_KokoroPhonemizer_phonemes(JNIEnv *env, jobject, jstring text) {
    std::lock_guard<std::mutex> lock(guard);
    if (!ready) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Kokoro phonemizer not initialized"); return nullptr; }
    const char *bytes = env->GetStringUTFChars(text, nullptr);
    if (!bytes) return nullptr;
    std::string input(bytes);
    env->ReleaseStringUTFChars(text, bytes);
    const void *cursor = input.c_str();
    std::string output;
    size_t clauses = 0;
    while (cursor && *static_cast<const char *>(cursor) && clauses++ < 1024) {
        const void *before = cursor;
        const char *ipa = espeak_TextToPhonemes(&cursor, espeakCHARS_UTF8, espeakPHONEMES_IPA | espeakPHONEMES_TIE | ('^' << 8));
        if (ipa && *ipa) { if (!output.empty()) output += ' '; output += ipa; }
        if (cursor == before) break;
    }
    return env->NewStringUTF(output.c_str());
}
