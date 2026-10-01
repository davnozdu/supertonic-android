# LLM preparation (Beta 8)

LLM processing is optional and disabled by default. It prepares Russian punctuation
and contextual stress before the normal dictionary and TTS pipeline. Cloud keys
are entered in settings and encrypted with Android Keystore; no credentials are
included in the APK. Local mode never sends text to a cloud provider.

## Providers and settings

- Ollama and Gemini model lists are fetched from their APIs. No model is selected
  until the user chooses one from the current list.
- Auto tries configured clouds in the chosen order, then downloaded Gemma 4.
  A manually selected cloud has priority and uses local Gemma on failure.
- Downloadable local Gemma 4 E2B uses LiteRT-LM, GPU with CPU fallback, verifies
  its pinned SHA-256, resumes partial downloads and requires a 64-bit device.
  The disk file stays installed; the engine unloads from RAM after configured idle time.
- Thinking has separate Ollama, Gemini and local switches, all default to off.
  Ollama `/api/show` capabilities determine boolean or named-level controls.
  Gemini 2.5 Flash receives a zero thinking budget; Pro uses its minimum budget.
  Gemini 3+ uses the minimum supported level when off. These models do not promise
  complete shutdown of reasoning, and settings explain the limitation.
  Local Gemma receives `ThinkingConfig(false, 0)` when off and a bounded budget when on.
  Unknown Gemini model families receive no unsupported thinking parameter.

## Ahead preparation and limits

The TTS Binder wrapper observes accepted incoming `speak` requests and forwards
the original transactions, callbacks and audio lifecycle to Android. Submitted
requests can be prepared while the current audio plays. Requests are batched with
up to 4000 characters of context and cached in a bounded text queue (96000 characters,
256 requests). This does not retrieve book text that the reader has not submitted.
The first uncached request can therefore still incur preparation latency.

The app's own playback also prefetches submitted sentences. Stop cancels its
pending LLM preparation. Turning processing off cancels pending work and clears
prepared text. HTTP errors, invalid output, deadlines or exhausted providers fall
back to normal dictionary processing.

Validation rejects changed words, numbers, quotation marks, paragraph counts,
compound-word hyphens and malformed stress marks. Explicit original stresses and
user lexicon overrides take priority. This protects text integrity; it does not
guarantee correct linguistic analysis or stress for every homograph.

The instruction permits only punctuation and stress placement, with temperature
zero. Quotes, brackets and paragraph boundaries must retain their positions.
Invented quote/bracket formatting is stripped when the original fragment contains
none; original quoted text still requires exact delimiter positions.
After validation, Russian number normalization reads whole integers (including
grouped thousands and numbers before punctuation) and decimal fractions as words.
LLMs retain digits verbatim; the app performs number spelling deterministically.

## Verification

GitHub CI builds the Android APK and runs text preparation, update and thinking
policy unit tests. Synthetic cloud tests with actual API access confirmed:

- DeepSeek v4.1 Flash accepts `think: false`; the short Russian test returned in
  about 0.85–1.21 seconds without a thinking trace, including correct `светло́`.
- Gemini 3.1 Flash Lite accepts `thinkingLevel: MINIMAL`; the short test returned
  in about 1.67 seconds with zero reported thought tokens.

These are individual requests, not a throughput guarantee. Local loading,
download resume, GPU performance and the Binder observation path require device
verification. Beta 8 has not yet been installed on the test phone while its user
is reading in Moon+ Reader.

Six synthetic paragraphs (2138 characters) took 4.75–9.70 seconds with DeepSeek
Flash and no reported thinking trace. All six responses from the strict prompt
passed the application's Kotlin validator after discarding invented quotes.
Gemini took 6.21–6.85 seconds but changed words/numbers in that larger sample;
such output is rejected and falls back. Eleven deterministic number cases passed
locally, covering punctuation, grouped thousands, decimal fractions, dates and
ordinal suffixes. These checks did not generate speech.

Primary API documentation:

- https://docs.ollama.com/capabilities/thinking
- https://docs.ollama.com/capabilities/structured-outputs
- https://ai.google.dev/api/generate-content#ThinkingConfig
- https://developers.google.com/edge/litert-lm/android
