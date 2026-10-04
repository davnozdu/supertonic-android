#include <jni.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include "sonic.h"

extern "C" {
void* ptt_create(const char*, const char*, const char*, const char*, float, int, int, int, int);
void ptt_destroy(void*);
void* ptt_stream_start(void*, const char*, const char*);
int ptt_stream_read(void*, float**, int*);
void ptt_stream_cancel(void*);
void ptt_stream_end(void*);
void ptt_free_audio(float*);
}

struct Engine {
    void* tts = nullptr;
    void* stream = nullptr;
    std::mutex stream_mutex;
    std::mutex synth_mutex;
};

static Engine* engine_from(jlong value) { return reinterpret_cast<Engine*>(value); }

extern "C" JNIEXPORT jlong JNICALL
Java_com_brahmadeo_supertonic_tts_pocket_NativePocketTts_nativeCreate(
        JNIEnv* env, jobject, jstring models, jstring voices, jstring precision,
        jfloat temperature, jint lsd_steps, jint threads, jint sentence_pause_ms,
        jint max_text_tokens) {
    const char* model_path = env->GetStringUTFChars(models, nullptr);
    const char* voice_path = env->GetStringUTFChars(voices, nullptr);
    const char* precision_value = env->GetStringUTFChars(precision, nullptr);
    auto* engine = new Engine();
    // The upstream C API defaults to a relative "models/tokenizer.model".
    // Android stores assets in the app-private model directory, so pass the
    // absolute tokenizer path explicitly.
    const std::string tokenizer_path = std::string(model_path) + "/tokenizer.model";
    engine->tts = ptt_create(model_path, voice_path, tokenizer_path.c_str(), precision_value,
                             temperature, lsd_steps, threads, sentence_pause_ms, max_text_tokens);
    env->ReleaseStringUTFChars(models, model_path);
    env->ReleaseStringUTFChars(voices, voice_path);
    env->ReleaseStringUTFChars(precision, precision_value);
    if (!engine->tts) { delete engine; return 0; }
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_brahmadeo_supertonic_tts_pocket_NativePocketTts_nativeSynthesize(
        JNIEnv* env, jobject, jlong value, jstring text, jstring voice, jfloat speed, jobject sink) {
    auto* engine = engine_from(value);
    if (!engine || !engine->tts) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(engine->synth_mutex);
    const char* utf8 = env->GetStringUTFChars(text, nullptr);
    const char* voice_utf8 = env->GetStringUTFChars(voice, nullptr);
    void* stream = ptt_stream_start(engine->tts, utf8, voice_utf8);
    env->ReleaseStringUTFChars(text, utf8);
    env->ReleaseStringUTFChars(voice, voice_utf8);
    if (!stream) return JNI_FALSE;
    { std::lock_guard<std::mutex> guard(engine->stream_mutex); engine->stream = stream; }
    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_audio = env->GetMethodID(sink_class, "onAudio", "([F)Z");
    sonicStream tempo = sonicCreateStream(24000,1);
    bool success = on_audio != nullptr && tempo != nullptr;
    if(tempo) sonicSetSpeed(tempo,speed);
    auto emit = [&]() -> bool {
        const int count = sonicSamplesAvailable(tempo);
        if(count<=0) return true;
        std::vector<float> samples(count);
        const int read = sonicReadFloatFromStream(tempo,samples.data(),count);
        jfloatArray chunk = env->NewFloatArray(read);
        if(!chunk) return false;
        env->SetFloatArrayRegion(chunk,0,read,samples.data());
        const jboolean accepted = env->CallBooleanMethod(sink,on_audio,chunk);
        env->DeleteLocalRef(chunk);
        if(env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return accepted;
    };
    while(success) {
        float* samples = nullptr;
        int count = 0;
        const int state = ptt_stream_read(stream,&samples,&count);
        if(state==0) {
            success=sonicFlushStream(tempo) && emit();
            break;
        }
        if(state<0) { success=false; break; }
        success=sonicWriteFloatToStream(tempo,samples,count);
        ptt_free_audio(samples);
        if(success) success=emit();
    }
    if(tempo) sonicDestroyStream(tempo);
    env->DeleteLocalRef(sink_class);
    { std::lock_guard<std::mutex> guard(engine->stream_mutex); engine->stream = nullptr; ptt_stream_end(stream); }
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_brahmadeo_supertonic_tts_pocket_NativePocketTts_nativeStop(JNIEnv*, jobject, jlong value) {
    auto* engine = engine_from(value);
    if (engine) { std::lock_guard<std::mutex> guard(engine->stream_mutex); if (engine->stream) ptt_stream_cancel(engine->stream); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_brahmadeo_supertonic_tts_pocket_NativePocketTts_nativeDestroy(JNIEnv*, jobject, jlong value) {
    auto* engine = engine_from(value);
    if (!engine) return;
    { std::lock_guard<std::mutex> guard(engine->stream_mutex); if (engine->stream) ptt_stream_cancel(engine->stream); }
    std::lock_guard<std::mutex> guard(engine->synth_mutex);
    ptt_destroy(engine->tts);
    delete engine;
}
