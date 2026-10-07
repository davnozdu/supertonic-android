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
    // One KV sequence per prompt kind (0 text, 1 roles): calls alternate between them, and each keeps its own
    // instruction prefix in the cache (one shared sequence re-prefilled ~400-600 tokens on every switch).
    static constexpr int SLOTS = 2;
    std::vector<llama_token> previous[SLOTS];
    std::atomic<bool> cancel{false};
    std::string stats;
};

bool abortCallback(void *data) { return static_cast<Handle *>(data)->cancel.load(); }

// One HTP serves QNN (Kokoro/Tera) and ggml-hexagon (Gemma); concurrent use failed with QNN 1002, so every
// llama_decode holds the app-wide fair lock (utils.Npu.lockHtp/unlockHtp) and calls interleave.
struct HtpTurn {
    JNIEnv *env; jclass npu; jmethodID lock, unlock;
    HtpTurn(JNIEnv *e) : env(e) {
        npu = env->FindClass("com/brahmadeo/supertonic/tts/utils/Npu");
        lock = npu ? env->GetStaticMethodID(npu, "lockHtp", "()V") : nullptr;
        unlock = npu ? env->GetStaticMethodID(npu, "unlockHtp", "()V") : nullptr;
        if (!lock || !unlock) { env->ExceptionClear(); lock = unlock = nullptr; }
    }
    int decode(llama_context *ctx, const llama_batch &batch) {
        if (lock) env->CallStaticVoidMethod(npu, lock);
        int r = llama_decode(ctx, batch);
        if (unlock) env->CallStaticVoidMethod(npu, unlock);
        return r;
    }
};

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
    cp.swa_full = true;  // the instruction prefix is reused across calls; partial removal needs the full SWA cache
    cp.n_seq_max = Handle::SLOTS;
    cp.kv_unified = true;  // both slots share the n_ctx cells: a long text prompt may use most of them
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
                                                                  jint maxTokens, jint jslot) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h == nullptr) { fail(env, "Gemma NPU not loaded"); return nullptr; }
    h->cancel = false;
    const char *chars = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(chars);
    env->ReleaseStringUTFChars(jprompt, chars);
    llama_sampler_reset(h->sampler);
    auto tokens = tokenize(h->vocab, prompt, true);
    const llama_seq_id slot = jslot >= 0 && jslot < Handle::SLOTS ? jslot : 0;
    const llama_seq_id other = 1 - slot;
    auto &previous = h->previous[slot];
    const int nCtx = (int) llama_n_ctx(h->ctx);
    if (tokens.empty() || (int) tokens.size() + maxTokens > nCtx) {
        fail(env, "Gemma NPU context: prompt " + std::to_string(tokens.size()) + " + output " + std::to_string(maxTokens) +
                  " > " + std::to_string(nCtx));
        return nullptr;
    }
    llama_memory_t memory = llama_get_memory(h->ctx);
    // The other slot gives its cells back when this call could not fit next to it.
    if (llama_memory_seq_pos_max(memory, other) + 1 + (int) tokens.size() + maxTokens > nCtx) {
        llama_memory_seq_rm(memory, other, -1, -1);
        h->previous[other].clear();
    }
    // Keep this slot's shared instruction prefix; decode only what differs (at least one token).
    size_t keep = 0;
    while (keep < previous.size() && keep < tokens.size() && previous[keep] == tokens[keep]) ++keep;
    if (keep >= tokens.size()) keep = tokens.size() > 0 ? tokens.size() - 1 : 0;
    if (keep == 0 || !llama_memory_seq_rm(memory, slot, (llama_pos) keep, -1)) { llama_memory_seq_rm(memory, slot, -1, -1); keep = 0; }
    previous.clear();
    llama_batch batch = llama_batch_init(128, 0, 1);
    struct BatchFree { llama_batch &b; ~BatchFree() { llama_batch_free(b); } } batchFree{batch};
    auto fill = [&](const llama_token *ids, int n, llama_pos pos) {
        batch.n_tokens = n;
        for (int k = 0; k < n; ++k) {
            batch.token[k] = ids[k]; batch.pos[k] = pos + k;
            batch.n_seq_id[k] = 1; batch.seq_id[k][0] = slot; batch.logits[k] = k == n - 1;
        }
    };
    using clock = std::chrono::steady_clock;
    auto t0 = clock::now();
    HtpTurn htp(env);
    for (size_t i = keep; i < tokens.size(); i += 128) {
        int n = (int) std::min<size_t>(128, tokens.size() - i);
        fill(tokens.data() + i, n, (llama_pos) i);
        if (htp.decode(h->ctx, batch) != 0) {
            fail(env, h->cancel ? "Gemma NPU cancelled" : "Gemma NPU prompt decode failed");
            return nullptr;
        }
    }
    auto t1 = clock::now();
    std::string out;
    int generated = 0;
    llama_pos pos = (llama_pos) tokens.size();
    for (; generated < maxTokens; ++generated) {
        if (h->cancel) { fail(env, "Gemma NPU cancelled"); return nullptr; }
        llama_token id = llama_sampler_sample(h->sampler, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, id) || id == h->turnEnd) break;
        char piece[256];
        int m = llama_token_to_piece(h->vocab, id, piece, sizeof(piece), 0, false);
        if (m > 0) out.append(piece, (size_t) m);
        fill(&id, 1, pos++);
        if (htp.decode(h->ctx, batch) != 0) {
            fail(env, h->cancel ? "Gemma NPU cancelled" : "Gemma NPU decode failed");
            return nullptr;
        }
    }
    auto t2 = clock::now();
    previous = tokens;
    double prefillMs = std::chrono::duration<double, std::milli>(t1 - t0).count();
    double decodeMs = std::chrono::duration<double, std::milli>(t2 - t1).count();
    char stats[192];
    snprintf(stats, sizeof(stats), "slot=%d prompt=%zu reused=%zu prefillMs=%.0f generated=%d decodeMs=%.0f decodeTps=%.1f", slot, tokens.size(), keep, prefillMs,
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
