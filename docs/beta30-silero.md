# Beta 30: Silero final syllables and background LLM preparation

The phone was on Silero v5.5 / kseniya, Ollama stress enabled, local stress disabled.
Read-only logs showed `provider=словарь, fallback=true`: those fragments were not
successful LLM preparations. A fallback must not be mistaken for an LLM result.

## Changes

- Adapt the model-input ending corrections documented in RuVoice's
  `Stress.modelEnd` and `Stress.stretchShort` (upstream commit 8a1bb42).
  Protect a non-final stressed vowel in a fragment without terminal punctuation
  using a synthetic dot with a one-frame override. Remove a single terminal dot
  after an explicitly stressed final syllable. Preserve questions, exclamations
  and ellipses. Stretch the final vowel of isolated short replies and stressed
  СМИ; do not stretch short words inside paragraphs.
- Apply these corrections only to the v5.5 RU model's synthesis input. Keep its
  natural prosody and the default disabled fixed-pause overrides. No PCM samples
  are removed, and no pauses between words are inserted.
- Background reader preparation waits up to 30 seconds for the existing LLM
  future; foreground startup still waits at most 1.5 seconds. A foreground
  timeout retains that future, allowing background and foreground to share work.
  Retained completed entries remain bounded by existing entry/character limits.
- Explain in settings that disabling the local accentor leaves Silero dependent
  on timely LLM results or the imported accent dictionary. User switches are
  not changed automatically. Add accent-count and end-correction logs without
  logging book contents.

## Verification

16 pure Kotlin/JUnit tests passed, including terminal stress, short final vowels,
LLM acute-to-native-plus propagation, mixed punctuation and Tera pause logic.
Android compilation and full unit tests run on GitHub. Audible quality still
requires listening on the phone after installation; waveform existence alone
does not verify pronunciation.

Reference: https://github.com/kost-t-human/ruvoice-tts/blob/8a1bb42/app/src/main/java/ru/kost/ruvoice/text/Stress.kt
