# Music lookup before generation · Beta231

Beta230 deliberately routed only scene ambience and effects through sound banks. Mood jobs (m<N>_<variant>) went straight to generation. Phone audit of the active preparation found55published real clips,18legacy clips,9new Freesound-cache clips,3generated airplane-cabin beds,1chapter transition and21mood clips. Thus music generation did not mean sound-bank lookup had failed.

Music now has a separate lookup path: installed GeneratedMusic, previously prepared book music (music processing version2), installed catalog tracks with explicit musical descriptions, then opt-in Freesound instrumental music. Untagged catalog tracks are skipped; current seven poetic track titles cannot establish mood or instrumentation. Freesound retains the existing switch, encrypted key, rate limits, missing-result cache, license whitelist and shared provenance index. The music path has a separate cache key, targets−12.5LUFS/−1dBpeak/AAC128kbps; it never turns a forest recording into music.

Exact local requests are preferred, excluding only the standard technical recording hint. Other matches require explicit instrumental/no-vocal evidence, mood and instrumentation; requested tempo, BPM (±3), key/mode, style, sparse texture and intensity must agree. Ambiguity goes to the generator. This is conservative metadata matching, not acoustic classification or guaranteed accuracy.

Downloads of the music model are deferred until missing music remains after lookup. All ready sources can be used without an installed generator; the mood-only-book missing calculation now includes its music. A catalog MP3 with suitable metadata is converted once to30sAAC; other prepared AAC is reused directly. Online sources retain their30–90s full duration.

Identical music requests are grouped by exact prompt, duration and variant index, preserving three variants while sharing the corresponding results between moods. The active preparation had7moods but4different descriptions:21jobs become12primary jobs plus9copies. No book-specific words/rules are added.

Validation:8pureJVM tests (mood/instrument/tempo/mode mismatch, unknown titles, vocal rejection, percussion restrictions, intensity/key, exact cached prompts, duplicate grouping with three variants preserved) and6existingFreesound tests passed locally. Android network/audio client compiled against the local Android API. Full Android/JVM build runs on GitHub. No phone playback is launched by the agent, and active preparation must not be interrupted by installation.
