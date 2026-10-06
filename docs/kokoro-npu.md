# Kokoro-RU on the Snapdragon NPU (experimental)

Both packages; the same "Ускоритель NPU" switch as Tera. For Q8 (uint8 ConvInteger weights with a per-tensor
scale/zero point) the segment graphs dequantize the weights with plain ops that ORT constant-folds, and the NPU runs
the convs in FP16 with FP16 activations (the CPU Q8 path also quantizes activations to 8 bit). Desktop check against
the CPU Q8 generator: SNR 31 dB, LSD 2.2–2.6 dB, i.e. within Kokoro's own run-to-run noise (~2.2 dB).

## Why a split executor

The iSTFTNet generator is ~90% of Kokoro's time. Two HTP defects were found on SM8850 / QNN 2.42:
`Pow(x, 2)` in Snake (tiny alpha ≈ 0.003) and `LeakyRelu(alpha = 0.01)` evaluate wrongly; `x*x` and
`max(x, a*x)` are exact replacements. The high-rate stage does not compile for phrases longer than
~2.4 s ("Failed to finalize QNN graph"), and AdaIN InstanceNorm needs statistics over the whole phrase,
so plain windows change the sound (LSD 3.5–6 dB vs Kokoro's own 2.2 dB run-to-run noise).

## Kit (`tools/kokoro_npu/build_kit.py`, assets `kokoro_npu/<model>/`)

- `pre.onnx` (CPU): text → generator input, both noise branches, AdaIN coefficients P = (1+γ)·w, Q = (1+γ)·b + β.
- 48 `seg_*.onnx` (NPU): `A·x + B → Snake → mask → Conv [+ residual]` between two norms, 32-frame chunks
  (640 / 3840 samples) with a 32-sample halo.
- `nup0/nup1/npost.onnx` (NPU): both upsamplers and conv_post in chunks (LeakyRelu as `max(x, a·x)`);
  `tail.onnx` (CPU): the iSTFT head. The reflection pad is an index shift in Kotlin.
- All 51 NPU graphs are compiled into ONE QNN context (`ep.share_ep_contexts`, external `_qnn.bin`,
  `.done` marker): 4.13.6 used one context per graph and held ~1.5 GB of dmabuf for both voices' models.
- The app computes mean/variance of every norm input over the full phrase: A = P/σ, B = Q − P·μ/σ.
- Initializers are external-data references into the pinned `model.onnx` / `model_dima.onnx`
  (sha256 in `kit.json`): no weights are duplicated, ~1 MB of graphs per model.

`tools/kokoro_npu/verify_kit.py` replays the orchestration on the desktop CPU: SNR 122 dB (Sveta/Masha
model) and 120 dB (Dima) against the untouched generator on the same front outputs.

## Runtime

Graphs compile in the background on first use (cached in `npu-cache`); until then chunks run on the CPU
model, afterwards the CPU model is released. Phrases above 800 frames (~20 s) stay on the CPU. Once compiled, a decoder loads synchronously (~2 s) instead of
keeping the CPU model resident; norm statistics are gathered while chunk results are copied out; the CPU parts use
at most two threads. Any NPU error
switches Kokoro back to the CPU and is remembered for this app version (`npu_failed_version_kokoro`), without
affecting Tera. Probe: `SpeechDiagnosticsActivity --ez kokoroNpuProbe true` (results also in
`files/npu-models/probe-results.txt`).

## Memory (SM8850, both voice models resident, 4.13.8–4.13.9 probes)

| Variant | dmabuf | Notes |
|---|---|---|
| one QNN context per graph (4.13.6) | 1.58 GB | 96 sessions |
| shared context, 32-frame chunks | 1.73 GB | 102 sessions; 34 × 32 MB HTP spill blocks (17 per model) |
| + `enable_htp_shared_memory_allocator=1` (default now) | 1.40 GB | same speed/CPU |
| 16-frame chunks | 1.57 GB | spill blocks unchanged, 2× calls |
| `vtcm_mb=2` | 2.86 GB | spill doubles (68 blocks) |
| `htp_graph_finalization_optimization_mode=1` | 2.09 GB | 45 blocks, faster compile |
| `enable_vtcm_backup_buffer_sharing=1` | — | compile fails; load-only: 3× slower |

Process PSS ~350 MB (CPU path ~1.2 GB). One voice model ≈ 0.35 GB PSS + 0.7 GB dmabuf. The spill blocks are
per graph inside one context; ORT shares spill-fill buffers only across contexts. Diagnostic prefs:
`npu_debug_qnn`, `npu_debug_cfg` ("k=v;k=v"), `npu_debug_kokoro_chunk` (frames).
