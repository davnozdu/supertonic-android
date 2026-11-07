# Beta229: preserve character identity throughout reading

Validated model spans are authoritative, including AUTHOR. Local rules now fill missing responses only. Replaying a real Kar­niz model response through the previous merge showed a narrator reply turned into generic MALE and an inline narrator span inserted into Kressner’s speech as MALE with no character. The new merge retains the original model plan.

Reader history is captured per client UID when each request arrives. Actual synthesis consumes that request’s preceding words; future requests and speculative preparation cannot advance its context. Explicit stop clears that client. Ahead audio plans are compared using character, role, voice and exact text boundaries. A long unknown fragment cannot inherit a character from one accidental external-context window.

## Validation

- 22 pure JVM tests, including the actual index + request-context path: three men, three women, an interruption, a return to a previous speaker, repeated identical short answers, distinct character voices, and queued future requests. These verify routing of supplied annotations, not semantic inference.
- Actual DeepSeek Flash responses on excerpts from three books: Kar­niz 18/18 reviewed checkpoints, Idiot 20/20, Suprugi Orlovy 14/14. Counts check speaker identity as well as gender. Six additional Idiot checkpoints were reviewed against the already recorded response, without another API call; these include unlabelled replies by Evgeny and Lebedev. This is a limited sample, not a whole-book accuracy guarantee.
- Existing phone Kar­niz index: 674 sentence cuts. Using context incorrectly advanced through the current request changed 60 cuts; correct preceding context produced zero whole-paragraph versus sentence-cut differences. This checks consistency, not correctness of the saved annotations.

No book names, character names or book-specific exceptions are added to production rules. The model prompt is unchanged. Existing prepared packages retain their saved annotations and need re-preparation to benefit from the merge fix. Shtorm speed and KOReader playback stalls are separate unresolved reports; no claim of fixing them here.
