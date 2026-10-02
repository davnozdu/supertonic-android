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

## Foreign-language passages

Tera and Silero share the optional Android TTS proxy for Latin passages. Settings
offer the system engine or an explicitly selected installed engine, and automatic,
English or Czech language selection. The current app and upstream Supertonic are
excluded. An internal request marker rejects accidental delegation back into this
service if Android cannot connect to the selected external engine.

Foreign audio is synthesized, resampled to our current mono PCM16 rate and sent
through the same bounded playback channel. The external engine does not speak
independently. Installed offline voices are preferred; the external engine's own
network policy applies. Connection wait is at most three seconds; synthesis eight
seconds. Stop is observed every 50 ms while waiting. Failures have a one-minute
cooldown per engine/language and use approximate transliteration so reading can
continue. A missing Czech voice does not disable a working English voice.
Transliterated fallback still expands complete numbers through our Russian
normalizer. Disabling delegation uses that same fallback for Latin passages.

The external audio LRU is limited to 32 MB / 128 entries. The engine disconnects
after two minutes idle. Numbers in foreign spans stay intact for that engine;
Russian numbers still use our validated LLM pipeline. A mixed source is split
without dropping punctuation/characters. Automatic Czech recognition uses script
and word hints, not a complete language detector; ambiguous Latin defaults to
English, with a manual Czech override.
