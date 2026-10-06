# Gemma 4 NPU experiment

Separate ARM64 APK/process/package `com.davnozdu.gemma4.npuprobe`. Does not modify
MyTTS, playback, selected TTS model, or LLM preferences. Build **only on GitHub**
with workflow `Gemma NPU Probe` on branch `experiment/gemma4-npu`.

Uses published RunAnywhere SDK / QHexRT 0.20.19, keyless local mode. The merged
manifest explicitly removes INTERNET; generation requests require QHEXRT/NPU
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
