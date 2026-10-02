# Silero v5_5_ru on Android

The optional Russian engine consumes the existing `ruvoice-pack-ru.zip` Android
pack: TorchScript Lite `tts_mel.ptl` / `head.ptl`, ExecuTorch `backbone.pte`.
There is no ONNX conversion or model export during build or installation.

Source: https://github.com/kost-t-human/ruvoice-tts (0.18.0).
Original model: https://models.silero.ai/models/tts/ru/v5_5_ru.pt
Model licence: **CC BY-NC-SA 4.0** (noncommercial, attribution, share alike).
The app labels this restriction beside the model selection.

The 82.5 MiB download is pinned by SHA-256. Extraction only accepts the four
known filenames and exact sizes. It publishes a complete verified directory;
partial downloads are never treated as a ready model. Files are separate from
Tera/Supertonic, so adding or deleting Silero preserves their downloads.

Five speakers: aidar, baya, kseniya, eugene, xenia; output mono PCM16 at 48 kHz.
Existing LLM preparation, explicit stresses, user lexicon, number normalization,
dictionary fallback and bounded PCM buffering are shared. The adapter translates
Unicode acute accents into Silero's `+` before the stressed vowel. It preserves ё,
commas, full stops, questions, exclamations, dashes and ellipses in the alphabet.
Unsupported characters are filtered as in the original model's text input.
English words need transliteration/user lexicon; this is a Russian-only model.

The separate intonation switch controls v5.5 `type_ids`: declarative,
wh-question, yes/no question, alternative question, tag question, exclamation.
This is sentence intonation, not arbitrary emotion/SSML tags. No LLM-generated
emotion commands are accepted. Question classification is heuristic, not a
complete syntax parser. Each sentence in a combined chunk receives its own type.

Inference is CPU/XNNPACK, ARM64 only. Models unload after two minutes of idle
time and reload on demand; idle release cannot interrupt synchronized synthesis.
The runtime uses the ARM64 native ExecuTorch library built by ruvoice 0.18.0
without LLM kernels and with outline atomics for older ARM64 processors.
`tools/prepare_silero_runtime.py` verifies that release APK and the official
ExecuTorch 1.5.0 AAR, and combines its matching Java API with this native library.
Both GitHub workflows prepare it before Gradle. No ruvoice source code is copied.

Runtime projects/licences:
- PyTorch (BSD): https://github.com/pytorch/pytorch/blob/main/LICENSE
- ExecuTorch (BSD): https://github.com/pytorch/executorch/blob/main/LICENSE

Runtime co-existence with ONNX Runtime / LiteRT-LM must be checked in the signed
APK on a real ARM64 device. Author benchmarks are not this app's measurements.
