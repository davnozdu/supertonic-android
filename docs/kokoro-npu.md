# Kokoro-RU on the Snapdragon NPU (experimental)

Only the full-precision package; the same "Ускоритель NPU" switch as Tera. Q8 stays on the CPU.

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
- `up0/up1/post.onnx` (CPU): upsampling, reflection pad, conv_post and the iSTFT head.
- The app computes mean/variance of every norm input over the full phrase: A = P/σ, B = Q − P·μ/σ.
- Initializers are external-data references into the pinned `model.onnx` / `model_dima.onnx`
  (sha256 in `kit.json`): no weights are duplicated, ~1 MB of graphs per model.

`tools/kokoro_npu/verify_kit.py` replays the orchestration on the desktop CPU: SNR 122 dB (Sveta/Masha
model) and 120 dB (Dima) against the untouched generator on the same front outputs.

## Runtime

Graphs compile in the background on first use (cached in `npu-cache`); until then chunks run on the CPU
model, afterwards the CPU model is released. Phrases above 520 frames (~13 s) stay on the CPU. Any NPU error
switches Kokoro back to the CPU and is remembered for this app version (`npu_failed_version_kokoro`), without
affecting Tera. Probe: `SpeechDiagnosticsActivity --ez kokoroNpuProbe true` (results also in
`files/npu-models/probe-results.txt`).
