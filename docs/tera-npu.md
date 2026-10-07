# Tera on the Snapdragon NPU (experimental, shared "Ускоритель NPU" switch)

- Vocoder: fixed 16/84-frame windows on the HTP (since 4.13).
- Sampler (hybrid, opt-in since 4.14.1: "Шаги синтеза на NPU" 0/1/2/4, default 0): `tools/tera_npu/build_step.py` turns ONE step of the 8-step ONNX Loop into a
  graph that references the pinned `sampler_distilled_cfg3_8step.onnx` weights by byte offset (also inside
  the Loop body). Exact rewrites: step-time encoder precomputed in FP32 (`time.bin`, it overflows in FP16 on
  the HTP), edge Pads replicate the last valid frame (`last_sel`) so frame padding with `latent_mask` is exact,
  Softplus -> relu(x) + log(1 + exp(-|x|)). Desktop: 8 steps with padding match the Loop at 90–105 dB.
- On SM8850 / QNN 2.42 steps 0–3 come back NaN on the HTP (compiler defect: a micro-graph of the same
  projection is clean, all optimisation modes / FP32 / BF16 fail the same way); steps 4–7 match the CPU at
  30–33 dB and run 6–7× faster (12–17 ms vs ~90 ms per step at 128×128). So steps 0–3 run the same step
  graph on the CPU, steps 4–7 on the NPU in buckets (frames 32/64/128/256 × text 64/128/256), one shared
  QNN context with shared weights; a non-finite NPU step is redone on the CPU.
- Probe: `SpeechDiagnosticsActivity --ez teraNpuProbe true` (latent/audio SNR vs the Loop, timings).
