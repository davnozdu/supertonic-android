# DeepSeek API · MyTTS 5.0.4-beta7

DeepSeek is a third cloud provider, independent of Ollama and Gemini. The default
API model `deepseek-flash` currently serves DeepSeek 4.1 Flash. The settings screen
loads the authenticated `/models` catalogue and keeps exact model IDs for selection.
No API credential is bundled in the APK. User keys use the existing Android Keystore
AES-GCM storage, a password field and the secure settings window.

The provider participates in text preparation, stress verification, voice roles,
role recovery and EPUB/FB2 character preparation. Manual DeepSeek mode falls back
to local Gemma on failure. Auto mode includes DeepSeek after the existing clouds;
users can select “В авторежиме сначала DeepSeek”. Existing provider selection is
preserved on upgrade. OFF and local reading modes do not call DeepSeek.

Requests use the official HTTPS endpoint, Bearer authentication, JSON output and
an explicit `thinking.type` switch (disabled by default for reading). The book
preparation switch remains independent and defaults to enabled, as before. Request
deadlines, cancellation, cooldowns and validation use the shared cloud pipeline.
Truncated responses are rejected. Cache settings include the provider/model and
thinking settings; book preparation identity contains the provider label/model.

Validation on 2026-10-08:

- Live `/models`: `deepseek-flash`, `deepseek-v4-pro`.
- One sequential, non-thinking Flash request: valid JSON, exact input words and
  Russian stress marks; 1.28 s for the short control phrase (not a book benchmark).
- Four JVM protocol tests passed: catalogue IDs/order, selected model and thinking,
  content excluding reasoning, truncated response rejection. Real LlmProviders
  compiled locally with Android/LiteRT adapters.
- Existing 121 JVM regression tests passed.
- Real BookPreparation with JVM Android adapters: DeepSeek manual/auto selection,
  existing Ollama priority, missing-key rejection, independent thinking; 40 main/
  verification calls sequential, cache reuse and cancellation without partial import.
- Android Release 37805242698 and CI 37805233357 succeeded; published prerelease
  https://github.com/davnozdu/supertonic-android/releases/tag/v5.0.4-beta7
  (versionCode 182, commit 191b4f64).
- Phone installation and UI validation postponed at the user's request.

Official API documentation:
https://api-docs.deepseek.com/api/create-chat-completion/
https://api-docs.deepseek.com/api/list-models/
