# Beta 31: LLM owns successful text preparation

Includes Beta 30's Silero ending corrections and longer background LLM wait.
Beta 30 release build was cancelled to include the requested LLM priority.

Validated LLM results now carry their provenance through the reader handoff,
the system TTS service, and the app's playback service. Such text skips the
second lexicon/punctuation-tweak, local accentor, bulk accent dictionary and yo
restoration passes. Tera also skips its internal word/yo dictionaries, converting
only explicit combining acute marks to its native plus-before-vowel format.
Silero receives the same explicit accents, converted to its own input alphabet.

PCM cache keys include the dictionary-bypass flag, so offline and LLM-only
synthesis cannot share an incorrectly prepared cached recording. OFF mode,
provider errors and startup timeouts retain the autonomous normalization path
and respect local settings. Logs explicitly identify LLM or offline fallback.

Added tests for provenance surviving a duplicate-text FIFO and cache clear,
and for Tera's explicit-only conversion leaving unmarked words and е unchanged.
The private phone diagnostic checks the full normalizer bypass and can test
synthetic text with the configured provider. It temporarily enables local stress
only when explicitly requested for an offline diagnostic and restores the setting.
