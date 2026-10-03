# Beta 33: local text protocol and Tera checks

Beta 32 device evidence: the local Gemma model now completes inference instead
of failing JNI, but the control request was rejected on both backends. GPU
returned `сле́бло` instead of `светло` (2.914 s); CPU inserted a detached plus
and `страда+ла` (5.043 s). Neither answer was accepted for speech.

Beta 33 requests ordinary U+0301 stress and uses labelled plain text instead
of JSON input. Validation remains strict. Native plus responses are still
adapted for compatibility; no wrong word is silently repaired by the adapter.
The silent probe now checks four cases including contextual е/ё and records
all outcomes rather than stopping at the first rejected response.

A process-wide diagnostic guard prevents concurrent diagnostic activities
from changing/restoring settings and stopping one another's TTS. Tera's normal
synthesis and pause settings are unchanged. Pure JVM checks for local transport,
strict validation, Tera punctuation and minimum silence: 30 tests passed.

Runtime quality and real Moon Reader LLM flow still require device verification
of this build; do not infer success from inference completion alone.
