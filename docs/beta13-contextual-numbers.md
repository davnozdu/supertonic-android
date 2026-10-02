# Beta 13: counted numbers before LLM

Plain integers are deterministically expanded before LLM preparation, so the model sees and stresses the spoken number in sentence context. The source value is retained by construction. Validation permits only gender variants `один/одна/одно` and `два/две` inside recorded generated numeric spans; all other number words and surrounding book words must remain unchanged. Losing `сто` or changing `сто` to `двести` is rejected. General case inflection is not yet permitted.

The ordinary dictionary fallback also handles gender for common directly following counted nouns, including stressed forms: `1001 имя` → `одна тысяча одно имя`, `1101 запись` → `одна тысяча сто одна запись`. Unknown nouns retain the existing neutral masculine form without LLM. Dates, times, decimals, percentages, degrees, ranges and ordinal suffixes use their existing downstream handlers.

Paragraphs and punctuation are preserved during expansion for LLM. Expanded requests are bounded to 4,000 characters for local Gemma and 8,000 for cloud models; oversized blocks fall back without putting the provider into cooldown. Exact-context caching from Beta 12 includes these validated expanded results.

Verification before GitHub build: 33 pure Kotlin JUnit tests passed, including count gender, marked nouns, compound formats, paragraph preservation and rejection of modified numeric values. Full Android/R8 checks and device tests are performed by the release workflow and after installing the signed APK.
