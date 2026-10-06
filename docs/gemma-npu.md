# Gemma 4 E2B on the Hexagon NPU (experimental local engine, off by default)

LLM settings → «Движок локальной Gemma»: **GPU · LiteRT** (default, unchanged) or **NPU · Hexagon**.
Exactly one local engine is active; switching unloads the other and clears prepared-text/PCM caches.

- Model: h2loop-ai/gemma-4-e2b-hexagon @1bb2044c, `gemma4-e2b-w4.gguf` Q4_0, 2 620 370 976 bytes,
  SHA-256 e5310072…8a16889, downloaded from Hugging Face (>2 GB, not mirrored on GitHub releases).
- Runtime: the same revision's prebuilt llama.cpp 0ef6e55 libraries (`tools/gemma_npu/runtime.json`, sizes and
  SHA-256 pinned): libllama, libggml(-base/-cpu/-opencl/-hexagon) and the V79/V81 DSP skels. CI stage:
  `tools/prepare_gemma_npu_runtime.sh` (fetch + llama.cpp headers at the same commit + our JNI bridge
  `app/src/main/cpp/gemma_npu`). No QNN, no root; FastRPC/CDSP from the app UID (proved by the probe APK).
- Bridge: persistent model + context (HTP0, all layers, ctx 4096, batch/ubatch 128, flash attention, greedy),
  Gemma 4 chat format `<bos><|turn>user\n…<turn|>\n<|turn>model\n`, stop on `<turn|>`/EOG, cancel via abort
  callback, UTF-8 bytes returned to Kotlin. Same prompts, limits, deadline, cancellation and response checks
  as the LiteRT path (`LlmProviders.localHexagon`); an NPU failure is reported, not replaced by GPU.
- Evidence so far (probe APK, branch experiment/gemma4-npu): decode 25–28 tok/s, prefill ~1300 tok/s, CPU time
  −76…87 % vs llama.cpp CPU2; LiteRT GPU warm decode 31–35 tok/s at ~4–5 % CPU (different runtime/quant).
  Energy is not measured yet — the comparison inside MyTTS is the purpose of this build.
