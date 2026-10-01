# Reader-independent preparation: feasibility

Investigated 2026-10-01 on Android 16 with the installed Moon+ Reader Pro.

## What Moon sends

The installed reader builds `speakLines` for its current text region and immediately submits all of them with `TextToSpeech.speak(..., QUEUE_ADD, ...)`. It requests the next region when `onDone` arrives for the last utterance. For an ebook the region extends to the end of the current loaded chapter region, rather than just the visible screen. The actual size depends on book format and reader loading/splitting.

Its division choices are comma, full stop, paragraph, page. For ebook/background reading it converts page division to paragraph division. Selecting page therefore does not provide a larger synthesis request in that path. Current settings were paragraph division and an enabled 500 ms speaking interval. Moon explicitly enqueues that silence; engine synthesis speed cannot remove it.

These findings come from inspecting the installed application's TTS calls and its settings UI. No proprietary source is included here. A runtime timing/queue experiment is still required.

## Android boundary

`TextToSpeechService.onSynthesizeText` is synchronous and called on one synthesis thread. An engine cannot retain `SynthesisCallback` and use it after returning. Audio delivery also blocks when the framework playback buffer is full (AOSP limits its unconsumed audio to approximately 500 ms). The app's existing producer can prepare the remainder of the current request in its own RAM channel, but cannot see later framework requests through this callback alone.

`UtteranceProgressListener.onDone` denotes completed audible playback. Calling `done` with no audio and playing through a separate player would change the reader's semantics: page advancement, highlighting, progress persistence, and end-of-book behavior would run ahead of actual speech. Once requests are completed, the normal service `onStop` is also insufficient to control a detached player in every state. Such a mode cannot be the transparent default for arbitrary readers.

Sources:

- https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener#onDone(java.lang.String)
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/speech/tts/TextToSpeechService.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/speech/tts/SynthesisPlaybackQueueItem.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/speech/tts/ITextToSpeechService.aidl

## Candidate autonomous mechanism

Observe incoming `speak` calls at the engine's Binder boundary before Android's serial synthesis queue. Keep bounded copies of queued text, isolated by caller. This makes already submitted future utterances available for batched LLM preparation and PCM prefetch without changing playback callbacks or requiring a reader-specific hook or root.

The framework service's `onBind` can be overridden to wrap and forward its Binder. However, the TTS Binder wire interface is internal, not a supported SDK contract. Parsing must fail open to normal TTS, preserve the original parcels, validate the interface/version, and never block the Binder thread for inference. Stop, flush, caller death, rejected requests, duplicate text and queue bounds all require explicit handling. Verify on-device before describing this as a working feature.

Pipeline after verification:

1. Capture text already submitted by a reader.
2. Group an approximate one/two-page window by character count, respecting request boundaries.
3. Prepare stresses in a background worker; preserve original words and punctuation, validate model output, and retain explicit user stress overrides.
4. Synthesize prepared upcoming requests into a bounded PCM cache, retaining voice/rate/model parameters.
5. Deliver cached audio through the ordinary request callback, so reader controls and completion remain accurate.
6. Auto chooses configured cloud providers, then a downloaded local model on failure/offline. Manual selection has priority. If preparation cannot meet the playback deadline, the existing dictionary path must remain available.

Limits: an engine cannot retrieve text that an arbitrary reader has not yet submitted. A reader that only sends the next paragraph after playback completion cannot provide guaranteed two-page lookahead through standard TTS. Complete book import into this app is the reader-independent way to guarantee access to future pages. GPU/local-LLM speed, provider availability, stress accuracy and uninterrupted playback must be measured; an LLM does not guarantee correct stresses.

## Current verified state

- Beta 7 release CI and unit tests passed.
- Beta 6's updater downloaded and verified Beta 7's asset; interactive installation was not validated end to end.
- Beta 7 installed over Beta 6 via `adb install -r`; version code 38 confirmed, with models/settings retained.
- No book text has been sent to cloud LLMs during this investigation.
- LLM providers and Binder prefetch are not implemented in Beta 7.
