# Gemma 4 NPU experiment

Separate ARM64 APK/process/package `com.davnozdu.gemma4.npuprobe`. Does not modify
MyTTS, playback, selected TTS model, or LLM preferences. Build **only on GitHub**
with workflow `Gemma NPU Probe` on branch `experiment/gemma4-npu`.

Uses published RunAnywhere SDK / QHexRT 0.20.19, keyless local mode. The merged
QHexRT generation requests require QHEXRT/NPU
and ON_DEVICE. No cloud or GPU fallback is accepted as a successful NPU test.

Text-only model: [Gemma 4 E2B HNPU](https://huggingface.co/runanywhere/gemma4_e2b_HNPU),
revision `c3bc94b2d0064ccdc78d993abe2c9fdb038aed3e`, `v81`, context 512 tokens.
Required files total **8,251,288,552 bytes** (~7.68 GiB). This is storage size,
**not measured RAM**. Large FP16 per-layer embedding table: 4,697,620,480 bytes.
Do not distribute model weights as an APK asset or Git commit.

```sh
python3 tools/gemma-npu-probe/download_model.py /tmp/mytts-gemma4-e2b-v81
adb -s SERIAL install -r probe.apk
adb -s SERIAL shell am start -n com.davnozdu.gemma4.npuprobe/.ProbeActivity
# Capability check first; copy files only if the runtime supports this device:
adb -s SERIAL shell mkdir -p /sdcard/Android/data/com.davnozdu.gemma4.npuprobe/files/models
adb -s SERIAL push /tmp/mytts-gemma4-e2b-v81 /sdcard/Android/data/com.davnozdu.gemma4.npuprobe/files/models/gemma4-e2b-v81
adb -s SERIAL shell am force-stop com.davnozdu.gemma4.npuprobe
adb -s SERIAL shell am start -n com.davnozdu.gemma4.npuprobe/.ProbeActivity --ez run true
```

Verify unlocked state and media_session before any trial. Do not interrupt active
reading. SHA256 of every model file is checked on both host and device before load.
JSONL results at external files `probe-results.jsonl`; logcat tag `GemmaNpuProbe`.

RAM policy for this experiment: at least 2 GiB MemAvailable before loading;
sample own PSS and system MemAvailable every 500 ms; stop **own probe process**
if MemAvailable <768 MiB, PSS >5 GiB, or runtime trial >240 s. Native/DSP loading
may allocate between samples; this is a precaution, not an absolute OOM guarantee.
PSS may omit DSP/GPU allocations: also collect global DMA-BUF, swap, and meminfo
externally. A budget stop is a failed trial under this policy, not proof that a
model can never fit on a 16 GB phone.

Success requires real generated responses on NPU, stable memory during load and
decode, and acceptable coexistence with MyTTS. Until measured, no memory-fit or
performance claim is justified. E4B is outside this trial.

The third request exercises up to 128 generated tokens; it tests RAM during
longer decode, not the literary quality or accent accuracy of the model.
Probe APKs use the repository's stable signing certificate; initial debug APKs
used temporary GitHub runner keys and must be replaced once with model files
preserved outside the package directory. Move files back under the new app UID
or restore their read permissions; do not uninstall MyTTS.

## Matched-request comparison

The same package can explicitly select `npu`, `gpu`, or `cpu`; run each backend
in a fresh process. LiteRT-LM 0.17.1 uses the same 2,588,147,712-byte E2B file as
MyTTS, SHA256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`.
Copy it into the probe's `files/models/gemma-4-E2B-it.litertlm`, preserving the
original. GPU requests must succeed on GPU; there is no CPU fallback in the probe.

```sh
adb -s SERIAL shell am force-stop com.davnozdu.gemma4.npuprobe
adb -s SERIAL shell am start -n com.davnozdu.gemma4.npuprobe/.ProbeActivity \
  --ez run true --ez benchmark true --es backend gpu
# Repeat with backend npu, or backend cpu --ei threads 2 / 4.
```

Benchmark runs `ProbeCases.story` and `ProbeCases.speech` twice, with fresh
conversations and no retained chat history. Instructions are placed directly in
the user turn on both engines: the NPU bundle declares no system-role markers,
and the first speech trial ignored a separate system instruction. Both use 512 context tokens,
128 output-token limits and greedy sampling. LiteRT thinking/speculation are
explicitly disabled. QHexRT owns its bundle's chat template; its SDK wrapper does
not expose a Gemma-specific thinking template override or public power-mode knob.
Formats and quantization differ (QHexRT decoder INT8 versus LiteRT bundle), so
this compares deployable backends for the task, not identical kernels or weights.

Wall time includes conversation setup/prefill; CPU time includes process overhead,
including the equal memory monitor. LiteRT reports decode token counts/throughput;
QHexRT's earlier decode-time field was zero, so use wall time for comparison.
Output can differ: inspect the speech result for changed/missing words, incomplete
text and accents before treating a faster backend as suitable for integration.
No battery charge/current measurement is required or performed by this comparison.
Energy per completed task depends on both average power and total duration;
CPU time alone does not establish energy savings.

## Alternative Q4 GGUF / ggml-hexagon runtime

The second path uses [H2Loop's E2B Q4 bundle](https://huggingface.co/h2loop-ai/gemma-4-e2b-hexagon),
pinned revision `1bb2044c313769541558f2c27fa67561894d0f26`. The GGUF is
2,620,370,976 bytes, SHA256 `e531007218dfab990486a5de7676a6932d6ea8dea233d1f698d7c21cf8a16889`.
Place it at external files `models/gemma4-e2b-w4.gguf` with read permission
for the probe UID. Keep weights outside Git and the APK.

`fetch_hexagon_runtime.py` checks the pinned 13 libraries against
`hexagon-runtime.json`. GitHub packages them with a small C JNI bridge;
it sets `ADSP_LIBRARY_PATH` before loading the native entry point. Native inference
runs under the ordinary application UID, with no root. Both V79/V81 DSP libraries
are packaged; the tested phone selected V81 / CDSP. This path does not use QNN.

```sh
adb -s SERIAL shell am force-stop com.davnozdu.gemma4.npuprobe
adb -s SERIAL shell am start -n com.davnozdu.gemma4.npuprobe/.ProbeActivity \
  --ez run true --es backend hexagon --es hexDevice HTP0
# Repeat in fresh processes with hexDevice none (CPU2), or GPUOpenCL.
# All three in one invocation: hexDevice all (aggregate CPU/RAM only).
# Actual 128-token story generation, ctx512, thinking disabled:
adb -s SERIAL shell am force-stop com.davnozdu.gemma4.npuprobe
adb -s SERIAL shell am start -n com.davnozdu.gemma4.npuprobe/.ProbeActivity \
  --ez run true --es backend hexagon --es hexDevice HTP0 --es hexMode cli
```

Native benchmark: identical GGUF/runtime, pp128 and tg128, two repetitions,
CPU2, batch/microbatch128, flash attention on, polling0. `hexagon_benchmark`
records stdout/stderr, return code, whole-invocation wall and CPU time, including
initialization and native warmup. Run `analyze_hexagon.py INPUT.jsonl OUTPUT.json`
to retain each invocation and its memory samples. The native CLI/bench entry
points are diagnostic bridges; production needs a persistent structured
generation API, cancellation and coordinated RAM admission with TTS.

The earlier benchmark APKs removed INTERNET. Native `llama-cli` at this revision
starts a loopback HTTP server internally, including for `-m local.gguf`; Android
requires INTERNET for those local sockets. The generation-enabled APK grants it.
It passes no `server-base`, model URL or remote repository argument. The first
CLI trial without permission failed before model load (`failed to get a free port`)
and is excluded from successful generation/memory results. Use the direct llama
API for production to avoid the diagnostic CLI's local server and process globals.
