# Experimental multi-voice reading — Beta 46.4

The switch is off by default, in LLM text preparation settings. Narrator, male and female voices are selected manually and saved per synthesis engine. This is voice routing, not translation or summarization.

Each selected role voice and every option in its dropdown has a Listen/Stop button. Preview uses the same short, explicitly accented sample and the chosen Android TTS voice, without modifying book settings. An app-UID-restricted engine parameter bypasses LLM, multi-voice routing, reader lookahead and music enqueue for that request only. One activity-owned client replaces its previous preview, ignores stale callbacks, and stops on leaving the settings screen. A private diagnostic renders every installed voice while multi-voice mode is enabled, verifying that a preview keeps its requested voice.

## Data path

Reader queue → validated stress/punctuation/ё preparation → role metadata → per-role voice → the existing PCM stream. Roles refer to contiguous indices in original whitespace units; application reconstructs all fragments from the validated text. Gaps, overlaps, changed paragraph IDs, fractional indices and incomplete coverage use the narrator for the affected paragraph. Uncertain roles use the narrator. Punctuation-only fragments join the neighbouring voice; adjacent equal roles merge.

Classification shares the existing single LLM worker and selected provider. Clouds classify batches of at most 8000 prepared characters, with up to 1800 characters of preceding context; local Gemma uses a smaller 1600-character batch and 600-character context. Normal queued batches are up to 4000 source characters (1000 for local mode). Context is isolated by client, bounded and reset on stop/flush/settings changes. It is not a permanent character database. Cloud processing and classification are separate requests in this experimental version. No extra foreground waiting is introduced: the existing 1500 ms deadline applies to the whole preparation, then narrator/offline fallback is used. Background requests can wait up to 30 s.

Both metadata and PCM are cached. Role metadata cache is context-dependent, bounded to 64 batches/128000 characters. Invalid model output cannot delete or repeat source words. Attribution can still be semantically wrong; confidence is not a guarantee.

## Cloud study, 2026-10-04

Held-out fragment: Gorky's *The Orlovs*, paragraphs 47–60 of the supplied EPUB, with preceding paragraphs 42–46. 14 paragraphs, 1462 source characters. Prompt includes a generic Anna dialogue example, no examples copied from this test fragment. Thinking disabled/minimal according to the provider. One request per provider: these timings are not a statistical benchmark.

| Provider/model | Wall time | Complete coverage | First spoken role |
|---|---:|---|---:|
| gemini-3.5-flash-lite | 1913 ms | 14/14 | 14/14 |
| deepseek-v4.1-flash | 1952 ms | 14/14 | 14/14 |

The first-role metric is limited: it checks the speaker gender of the beginning, not every word. Inspection found Gemini assigning the last sentence of paragraph 56 to the narrator instead of continuing the male voice. DeepSeek handled this continuation correctly in this sample. Both preserve full source coverage. Local Gemma timed out on full JSON/index generation on the device. A compact bare-label protocol completed in 605 ms but omitted one label in the five-fragment synthetic check; the whole paragraph safely used the author. The local protocol now requests explicitly numbered А/М/Ж labels, one line per fixed dash-delimited fragment, with the exact count and last index stated in the prompt. Output budget scales from 64 to 272 tokens for at most 32 fragments. During role preparation, validated LLM text is independently available: playback uses the author voice rather than reverting good text to dictionaries. Indexed local labels require retesting; malformed/late output falls back safely. Mistral removed from recommendations at the user's request; provider catalogues are still fetched dynamically.

## Buffer corrections

The old PCM cache had a separate 256-entry ceiling, independent of its MB limit: 256 short 80 KB fragments retain only ~20 MB. The safeguard is now 16384 entries, so the byte budget is the practical limit for speech. Tera/Silero's former 16-task lookahead now shares the bounded 256-task/192000-character window; no silent discard. Prepared text handoff matches this window.

Upcoming unplayed PCM is protected; already delivered entries are evicted first. When future PCM approaches the chosen limit, only the background producer waits, reserving 16 MB for an in-flight fragment. Consumed/fallback requests release their own reservation. PCM also respects the actual managed-heap limit, reserving 64–128 MB for synthesis/playback. This phone allows a 512 MB large heap: the selected 256 MB is fully usable, while impossible 512/1024 MB choices are capped at 384 MB and the settings explain the effective limit. Logging reports retainedBytes, aheadBytes, limitBytes and entry count. The cache cannot invent text that the reader has not submitted; 256 MB is a maximum, not a promise that every reader supplies that much future text.

## Verification

JVM regressions cover exact source reconstruction (including Unicode book spaces and accents), missing/overlapping/repeated ranges, unknown attribution, punctuation-only fragments, context isolation, provenance handoff, byte-budget fill beyond 256 fragments, protected future PCM and release of consumed reservations. GitHub builds the Android APK and runs the full release test suite. The private multivoice probe stages text through LLM and actual Android synthesizeToFile, logging voice routing without playback. Listening is still needed to judge transitions and expression.

On-device Beta 46.2: DeepSeek and the configured Gemini 3.1 Flash Lite both returned all three roles and generated Tera WAV files. Source fingerprints followed validated text into the synthesizer, with internal stress dictionaries bypassed. Gemini preserved the single input element containing three paragraphs after the count instruction fix. Cache retrieval took less than 1 ms; Tera first PCM was ready in 212–228 ms, synthesis completed in 1316 ms. Cold cloud preparation plus roles took 12.2–12.4 s in these runs, so live reading still depends on reader lookahead and falls back to the author at the normal foreground deadline. Local Gemma on GPU processed text in 3499 ms after cold load, adding stress and ё; dictionary supplementation filled missing marks without replacing LLM marks. Its valid prepared text reached Tera despite invalid role labels. Silero also passed the full cloud role-to-voice pipeline (342 ms synthesis, 262 ms first PCM). Configured cache limit was 268435456 bytes on every run. A locked-screen diagnostic timed out because the vendor put its non-playback process into virtual freeze; preferences were restored before unlocked reruns.
