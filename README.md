# MyTTS for Android

Fork of [DevGitPit/supertonic-android](https://github.com/DevGitPit/supertonic-android) upgraded from Supertonic 2 (5 languages) to **Supertonic 3 (31 languages + `na` fallback)** using the [supertonic-3 ONNX weights from Hugging Face](https://huggingface.co/Supertone/supertonic-3).

The on-device inference pipeline (Rust + ONNX Runtime + XNNPACK) is unchanged — Supertonic 3 reuses the same four-stage graph (`text_encoder` → `duration_predictor` → `vector_estimator` → `vocoder`) and the same tensor shapes as v2. The fork therefore replaces only the asset bundle, the language tagging, and the surrounding UI; all inference code paths are identical.

## What changed vs. the upstream fork

| Area | Upstream (v1 + v2) | This fork (v3) |
|---|---|---|
| Languages | 5 (en, ko, es, pt, fr) | 31 + `na` fallback |
| Models | Two parallel dirs (`v1`, `v2`), bundled English + downloadable multilingual | Single Supertonic 3 download (~400 MB), one-time |
| Language tagging | `<lang>` only for non-English | Always wrapped (the model has no separate lang embedding) |
| Dash handling | Em-dash → comma only in English path | Em-dash → comma in every language (fixes Russian "Москва — столица" mis-reading) |
| System TTS settings | Reachable only via Android Settings | Direct shortcut from the in-app menu |
| Lexicon | Hand-edited rules (regex / whole word) | Same, plus **bulk accent dictionary import** for tens of thousands of stress-marked words |

## Install

Grab the signed APK from the **[Releases](https://github.com/davnozdu/supertonic-android/releases)** page.

1. On the phone: Settings → Apps → Special access → Install unknown apps → allow your file manager / browser.
2. Tap the APK to install. If `adb install` returns `INSTALL_FAILED_VERIFICATION_FAILURE`, disable **Verify apps over USB** in Developer Options.
3. First launch downloads ~400 MB of ONNX models from Hugging Face — needs Wi-Fi once.

Releases use the permanent keystore from repository secrets and install over the current fork without uninstalling. Application ID: `com.davnozdu.supertonic.tts.fork`.

## Use as the system TTS engine

After installation:

1. Open Supertonic TTS → menu → **System TTS Settings** (or Android Settings → Accessibility → Text-to-speech output).
2. Set **Preferred engine** to *Supertonic TTS*.
3. Pick any of the 31 languages and test with *Listen to an example*.

Every app that uses Android's TTS API (Voice Aloud, TalkBack, reader apps, navigation, Tasker etc.) will now use Supertonic.

## Background music

Menu → **Фоновая музыка**. **Скачать музыку** installs four Gemini-generated tracks from the project's `reading-music-v1` release (about 16 MB). Choose an installed track, or import your own MP3; the app copies it into private storage. Each track can be removed from the phone and the ready catalog can be downloaded again.

Music loops during actual reading, follows pause/stop and resumes at the saved position. Volume is independent of speech, 0–100%, default 10%. Speculative LLM preparation and synthesis to a file do not start music. The music decoder releases after two idle minutes. It creates no competing media session or audio-focus request.

## Shtorm PocketTTS RU v2 (experimental)

The ARM64 picker includes [ArtShtorm/Shtorm_PocketTTS_RU](https://huggingface.co/ArtShtorm/Shtorm_PocketTTS_RU), fast v2. Its ONNX graphs are prepared once by GitHub Actions, numerically compared with the original PyTorch model, and stored in the `shtorm-pocket-v2` release. The phone downloads approximately 439 MB and checks pinned SHA-256 hashes. No conversion runs on the phone.

The native runtime adapts [PocketTTS-Android-Engine](https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine) and PocketTTS.cpp. Output is 24 kHz mono PCM16. Shtorm receives the shared LLM/local text preparation and combines acute stress marks; phrases are bounded to approximately 180 characters as recommended by the author. Sonic adjusts speed without pitch shift. The model unloads after two idle minutes. Starter voice: Alba MacKenna, CC BY 4.0; model and runtime attribution are in `vendor/pockettts` and the downloadable license.

Tera and Silero remain available. Obsolete INT8/FP32/FP16 presets are hidden from the model picker.

## Pronunciation control

### TeraTTSv2 Russian preset

The model picker recommends [TeraTTSv2](https://huggingface.co/TeraSpace/TeraTTSv2) for Russian. It downloads the pinned distilled ONNX sampler, encoder, duration predictor, vocoder, four Russian voice styles, and the RUAccent yo dictionary from Hugging Face. Its [ready-to-use binary stress index](https://github.com/davnozdu/supertonic-dictionaries/releases/download/russian-v1.1/tera_accents.sacc) comes from the project's dictionary repository. Model files are not included in the APK. `ru_f1` is the default voice.

The Android implementation uses TeraTTSv2's **dictionary** stress mode. Its 3,194,879-entry `.sacc` index is memory mapped and needs no on-device conversion. Existing installs with the older JSON asset can still convert it as a fallback. Known words receive a stress marker automatically. A manual `+` before the stressed vowel or a combining acute accent after it takes priority. Unknown words and ambiguous homographs may remain unmarked. The upstream model's full neural RUAccent mode requires additional large ONNX models and tokenizers and is not enabled in this preset.

Tera preserves punctuation when splitting long text and inserts spaces after punctuation in the same way as the upstream runtime. Its only duration control scales the whole utterance; there is no model parameter for comma-only pause length. The optional punctuation switches in Lexicon remain available for listening comparisons.

TeraTTSv2's sampler generates the latent for a sentence before its vocoder can stream the first PCM chunk. It may take longer to start than an optimized Supertonic preset; compare on your device before using it as the system engine for a reader.

### Reading in Moon+ Reader

The system TTS service streams PCM chunks as soon as they are available. A bounded in-memory queue holds already generated chunks while Android consumes earlier audio. The in-app player likewise synthesizes later sentences while earlier sentences play. Android calls `onSynthesizeText` serially, so the engine can pre-generate only text that Moon+ Reader has already submitted; it cannot fetch future paragraphs from the reader on its own.

Two layers of user rules, both applied before the text reaches the model:

1. **Lexicon** (menu → Lexicon) — small set of hand-edited rules with regex or whole-word matching. Highest priority.
2. **Accent dictionary** (menu → Lexicon → Import accent dictionary…) — bulk JSON map for stress / pronunciation, e.g. open-source Russian stress dictionaries. Indexed by word, so a 50 000-entry dictionary still runs in milliseconds.

Expected accent dictionary shape:

```json
{
  "замок": "замо́к",
  "Москва": "Москва́",
  "молоко": "молоко́"
}
```

Stress is marked with the combining acute accent **U+0301** placed *after* the stressed vowel. Whether the model actually pronounces the marked syllable as stressed depends on its training data — try a short test through Lexicon first before importing a large file.

Ready-to-import Russian dictionaries (962 K and 615 K entries) live under [`dictionaries/`](dictionaries/) — download the JSON from the release assets, import via the menu.

## Build

CI builds (`.github/workflows/ci.yml`) reproduce locally:

```bash
# Requirements: Android SDK + NDK r29, JDK 17, Rust stable with Android targets
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # unsigned release APK; sign separately with apksigner
```

The Rust crate under `rust/` produces `libsupertonic_tts.so` for `aarch64`, `armv7`, `i686`, `x86_64`. ONNX Runtime is linked dynamically via `onnxruntime-android` from Maven.

## Credits

- [Supertone](https://github.com/supertone-inc/supertonic) — Supertonic 3 model weights, training, and the reference Python pipeline.
- [DevGitPit/supertonic-android](https://github.com/DevGitPit/supertonic-android) — upstream Android app with Compose UI, Rust JNI bridge, F-Droid metadata, and thermal management. This fork is a thin layer on top.

## License

Same as upstream. The Supertonic model weights are released under [OpenRAIL-M](https://huggingface.co/Supertone/supertonic-3) and are downloaded at runtime; they are not bundled in the APK.
