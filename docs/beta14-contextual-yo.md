# Beta 14: contextual ё

The new independently persisted `restore_yo` switch defaults on. The narrow LLM instruction allows only contextual е→ё restoration and preserves an explicitly written ё. Validation permits that exact letter substitution, restores original case and rejects other rewriting or ё→е loss. It also accepts ё as an inherently stressed vowel when checking for a completely missing stress response.

Turning the switch off discards proposed new ё while preserving source ё. Explicit stress remains authoritative. The generic dictionary cannot rewrite words already containing ё; Tera's own stress routine retains the letters of its input when adding dictionary stress.

This permits contextual disambiguation, not a proof of linguistic correctness: the LLM can still pick the wrong sense of an ambiguous word. Unit tests cover preservation, case, the independent switch and rejection of unrelated changes. Real-device tests check the chosen provider on controlled contextual examples.
