# Beta 34: local Gemma as an autonomous alternative

User request: do not demand perfect local LLM output. Retain valid local edits
and send unprocessed parts through standard autonomous filtering. Cloud replies
still bypass local dictionaries after validation.

Beta 33 device results: GPU restored `зелёный ребёнок` and distinguished
`все ученики` / `всё готово`, but omitted ё in `ёлкой` and omitted stress.
CPU omitted stress and rewrote `ученики` as `учёные`; that rewrite was rejected.

The validator now permits missing stress only for local provider calls. It
continues checking spelling, numbers, word boundaries, paragraphs and stress
positions. After validation, the existing local accentor and dictionary provide
candidates. A separate merge accepts only marks for previously unmarked words:
explicit LLM/user stress and written ё, source punctuation, letters, numbers and
paragraph boundaries are preserved. Ambiguous dictionary-only ё substitutions
are withheld. Switches for stress and ё remain independent.

The completed result is cached once and follows the existing LLM-prepared path
through both Tera and Silero; neither engine reapplies its dictionaries.
Runtime logs distinguish local supplementation. No cloud validation is relaxed.

Local prompt uses newly capitalized vowels as stress markers; the adapter only
interprets capitals newly added to matching Russian source words. The final
strict validator still rejects rewritten words or multiple accents. Private
silent diagnostics compare prompt forms without logging book contents.
