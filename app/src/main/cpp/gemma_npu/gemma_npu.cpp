// Persistent Gemma 4 E2B (Q4_0 GGUF) on the Snapdragon Hexagon NPU through llama.cpp's ggml-hexagon backend.
// Pinned prebuilt runtime: h2loop-ai/gemma-4-e2b-hexagon @1bb2044c, llama.cpp 0ef6e55 (headers from the same
// commit). One model + context per handle; generation is greedy and cancellable (abort callback + flag).
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <string>
#include <vector>
#include "llama.h"
#include "ggml-backend.h"

#define TAG "GemmaNpu"

namespace {
struct Handle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_token turnEnd = -1;  // <turn|>
    std::atomic<bool> cancel{false};
    std::string stats;
};

bool abortCallback(void *data) { return static_cast<Handle *>(data)->cancel.load(); }

void fail(JNIEnv *env, const std::string &message) {
    __android_log_print(ANDROID_LOG_WARN, TAG, "%s", message.c_str());
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool special) {
    int n = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, special, true);
    std::vector<llama_token> tokens(n > 0 ? n : 0);
    if (n > 0) llama_tokenize(vocab, text.data(), (int32_t) text.size(), tokens.data(), n, special, true);
    return tokens;
}

void release(Handle *h) {
    if (h->sampler) llama_sampler_free(h->sampler);
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_brahmadeo_supertonic_tts_llm_GemmaHexagon_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint nCtx,
                                                              jint threads, jstring jdevice) {
    static bool initialized = false;
    if (!initialized) { llama_backend_init(); initialized = true; }
    const char *name = env->GetStringUTFChars(jdevice, nullptr);
    std::string device(name);
    env->ReleaseStringUTFChars(jdevice, name);
    ggml_backend_dev_t dev = ggml_backend_dev_by_name(device.c_str());
    if (dev == nullptr) { fail(env, "Hexagon device " + device + " not available"); return 0; }
    ggml_backend_dev_t devices[2] = {dev, nullptr};
    llama_model_params mp = llama_model_default_params();
    mp.devices = devices;
    mp.n_gpu_layers = 999;
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    auto *h = new Handle();
    h->model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (h->model == nullptr) { release(h); fail(env, "Gemma NPU model load failed"); return 0; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_batch = 128;
    cp.n_ubatch = 128;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    h->ctx = llama_init_from_model(h->model, cp);
    if (h->ctx == nullptr) { release(h); fail(env, "Gemma NPU context failed"); return 0; }
    llama_set_abort_callback(h->ctx, abortCallback, h);
    h->vocab = llama_model_get_vocab(h->model);
    h->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(h->sampler, llama_sampler_init_greedy());
    auto end = tokenize(h->vocab, "<turn|>", false);
    if (end.size() == 1) h->turnEnd = end[0];
    __android_log_print(ANDROID_LOG_INFO, TAG, "loaded device=%s ctx=%d threads=%d turnEnd=%d", device.c_str(), nCtx, threads, h->turnEnd);
    return reinterpret_cast<jlong>(h);
}

// Returns UTF-8 bytes: a token limit may cut a multi-byte character, which NewStringUTF must not see.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_brahmadeo_supertonic_tts_llm_GemmaHexagon_nativeGenerate(JNIEnv *env, jobject, jlong handle, jstring jprompt,
                                                                  jint maxTokens) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h == nullptr) { fail(env, "Gemma NPU not loaded"); return nullptr; }
    h->cancel = false;
    const char *chars = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(chars);
    env->ReleaseStringUTFChars(jprompt, chars);
    llama_memory_clear(llama_get_memory(h->ctx), true);
    llama_sampler_reset(h->sampler);
    auto tokens = tokenize(h->vocab, prompt, true);
    const int nCtx = (int) llama_n_ctx(h->ctx);
    if (tokens.empty() || (int) tokens.size() + maxTokens > nCtx) {
        fail(env, "Gemma NPU context: prompt " + std::to_string(tokens.size()) + " + output " + std::to_string(maxTokens) +
                  " > " + std::to_string(nCtx));
        return nullptr;
    }
    using clock = std::chrono::steady_clock;
    auto t0 = clock::now();
    for (size_t i = 0; i < tokens.size(); i += 128) {
        int n = (int) std::min<size_t>(128, tokens.size() - i);
        if (llama_decode(h->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) {
            fail(env, h->cancel ? "Gemma NPU cancelled" : "Gemma NPU prompt decode failed");
            return nullptr;
        }
    }
    auto t1 = clock::now();
    std::string out;
    int generated = 0;
    for (; generated < maxTokens; ++generated) {
        if (h->cancel) { fail(env, "Gemma NPU cancelled"); return nullptr; }
        llama_token id = llama_sampler_sample(h->sampler, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, id) || id == h->turnEnd) break;
        char piece[256];
        int m = llama_token_to_piece(h->vocab, id, piece, sizeof(piece), 0, false);
        if (m > 0) out.append(piece, (size_t) m);
        if (llama_decode(h->ctx, llama_batch_get_one(&id, 1)) != 0) {
            fail(env, h->cancel ? "Gemma NPU cancelled" : "Gemma NPU decode failed");
            return nullptr;
        }
    }
    auto t2 = clock::now();
    double prefillMs = std::chrono::duration<double, std::milli>(t1 - t0).count();
    double decodeMs = std::chrono::duration<double, std::milli>(t2 - t1).count();
    char stats[192];
    snprintf(stats, sizeof(stats), "prompt=%zu prefillMs=%.0f generated=%d decodeMs=%.0f decodeTps=%.1f", tokens.size(), prefillMs,
             generated, decodeMs, generated > 0 ? generated * 1000.0 / decodeMs : 0.0);
    h->stats = stats;
    jbyteArray result = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(result, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_brahmadeo_supertonic_tts_llm_GemmaHexagon_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h != nullptr) h->cancel = true;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_brahmadeo_supertonic_tts_llm_GemmaHexagon_nativeStats(JNIEnv *env, jobject, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    return env->NewStringUTF(h == nullptr ? "" : h->stats.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_brahmadeo_supertonic_tts_llm_GemmaHexagon_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h != nullptr) release(h);
}
