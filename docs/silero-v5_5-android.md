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

## Beta 18: voice packs and offline preparation

Resources are mirrored in [russian-resources-v1](https://github.com/davnozdu/supertonic-android/releases/tag/russian-resources-v1): original ru (5 voices), CIS Russian (29 voices), offline accentor/homograph model, safe ё SACC dictionary, verified ARM64 runtime, licences and SHA-256 manifest. Downloads validate hashes and extract only known entries with exact byte limits. Existing Tera, ru and CIS files are kept separately. Installed resources need no network and survive app updates.

Select **Silero CIS · 29 русских голосов** in the model list, install its pack and select a voice. CIS has a different alphabet and speaker IDs, and takes 11 mel inputs, not v5.5's 13. It does not support the sentence-type/focus controls. LLM preparation, offline normalization, accents, ё and foreign TTS still apply.

The offline book normalizer adds case-aware cardinal numbers for recognized governing prepositions, whole integers and gender, dates, clock time, formatted Russian phone numbers, ordinal years, fractions, decimals, units/currency/percent/degrees, contextual Roman numbers, common abbreviations, named letter acronyms, footnotes and line-break hyphenation. SIM/PIN/GIF/MIDI/WIFI, Wi-Fi and hi-fi are expanded before foreign language detection. It is not a complete Russian grammatical parser; ambiguous grammar and unusual formats remain conservative. Source words and explicit stress are retained.

Local stress uses existing Android Silero Stress models from ruvoice 0.18.0 and its published dictionaries/vocabulary. No TTS conversion. The author grammar/phrase rule code is not copied. Word predictions are batched; homographs receive bounded context and BERT markers. The safe ё table is compiled once on the computer into SACC and mmap'ed on the phone. LLM/user stress and written ё win. Neural inference is bounded by input chunk size and unloaded after two minutes idle; sentence results have an LRU cache. As with LLM, stress prediction is probabilistic, not guaranteed perfect.

Android Binder queue observation already exposed early text to LLM. It now also submits QUEUE_ADD chunks to a bounded audio-ahead worker. Original Android transactions and playback acknowledgements are unchanged; cancelled/rejected requests are not played. Completed PCM has a shared LRU cache, adjustable 64–1024 MB (default 256 MB), 256 entries, individual PCM maximum 16 MB. Cache keys include model, exact prepared text, voice, speed, synthesis settings and foreign engine. A book cannot be prefetched before its reader has queued the text.

Both Russian engines account for existing trailing silence when adding a minimum sentence pause. Silero uses native per-character durations for comma/colon/dash pauses and keeps full native sentence intonation where supported.
