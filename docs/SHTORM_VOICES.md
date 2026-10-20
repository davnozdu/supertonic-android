# Shtorm voice references

Seven optional voice references from Kyutai tts-voices, revision `323332d33f997de8394f24a193e1a76df720e01a`, CC BY 4.0. Converted to mono 24 kHz PCM16 without shortening.

These are compatible references for Russian Shtorm voice cloning, not additional Shtorm languages and not a claim to reproduce the author’s seven unpublished evaluation voices. Shtorm itself supports Russian; foreign language fallback remains separate.

- alba: https://huggingface.co/kyutai/tts-voices/blob/323332d33f997de8394f24a193e1a76df720e01a/alba-mackenna/casual.wav
- marius: https://huggingface.co/kyutai/tts-voices/blob/323332d33f997de8394f24a193e1a76df720e01a/voice-donations/Selfie.wav
- javert: https://huggingface.co/kyutai/tts-voices/blob/323332d33f997de8394f24a193e1a76df720e01a/voice-donations/Butter.wav
- jean: https://huggingface.co/kyutai/tts-voices/blob/323332d33f997de8394f24a193e1a76df720e01a/ears/p010/freeform_speech_01_enhanced.wav
- fantine: https://huggingface.co/kyutai/tts-voices/blob/323332d33f997de8394f24a193e1a76df720e01a/vctk/p244_023_enhanced.wav
- cosette: https://huggingface.co/kyutai/tts-voices/blob/323332d33f997de8394f24a193e1a76df720e01a/expresso/ex04-ex02_confused_001_channel1_499s.wav
- eponine: https://huggingface.co/kyutai/tts-voices/blob/323332d33f997de8394f24a193e1a76df720e01a/vctk/p262_023_enhanced.wav

License: https://creativecommons.org/licenses/by/4.0/
Source attribution: https://huggingface.co/kyutai/tts-voices

## v2 slow (5.0.5-beta8)

Shtorm-PocketTTS-RU v2 ships two weight sets: `model_fast` (livelier, the base package `shtorm-pocket-v2`)
and `model_slow` (calmer delivery, freer pauses). `tools/export_shtorm.py slow` (workflow
`shtorm-slow-model.yml`) exports slow and publishes only graphs whose bytes differ from fast to release
`shtorm-pocket-v2-slow`: `flow_lm_main.onnx`, `mimi_encoder.onnx` (holds the speaker projection),
`text_conditioner.onnx`, ≈341 MB; `flow_lm_flow`/`mimi_decoder`/tokenizer are shared. Source revision
33f6c328 (weights unchanged since 01.10.2026). On the phone the slow files live in `shtorm-pocket-v2/slow/`
with symlinks to the shared fast files; this folder is also the native voice cache (`.cache/*.emb|*.kv`
depend on the weights). Choice: main screen under the voice style (ui/ModelPackageChoice, also Kokoro Q8/full), pref `shtorm_slow` (default fast).
