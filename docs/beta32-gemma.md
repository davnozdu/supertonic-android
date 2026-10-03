# Beta 32: local Gemma execution

Compared with the existing checkout of `davnozdu/autoresponder-app`,
`llm/LocalTextModel.kt`: same Gemma E2B file and LiteRT-LM 0.17.1, but that app
uses normal text conversations and retries inference failures on CPU.

Supertonic previously used JSON-constrained decoding, a large shared cloud
prompt, a fixed 6000-token output cap and a 12-second normal local deadline.
Real-device tests on Beta 31 failed on both GPU and CPU. With 45 seconds a
60-character GPU request finished in 15.4 seconds, but its answer was rejected.

Changes:

- Normal text conversations with a concise, local-specific editing instruction.
  Process individual fragments with bounded adjacent context. The model can
  return native plus-before-vowel stress; the adapter converts it to the common
  acute format before the existing strict validator. No validator restrictions
  on word preservation, numbers, hyphens or stress positions are removed.
- Stream each validated local fragment immediately to its waiting reader,
  retaining successful neighbours even when a later fragment fails.
- Use a bounded output cap derived from text length, a 45-second per-fragment
  background deadline, and explicit timeout errors. Foreground startup still
  waits at most 1.5 seconds.
- Retry GPU inference errors once on CPU; use the default CPU thread policy and
  CPU audio backend as in the working integration. Release cancelled/stale
  conversations and retain configured idle unloading.
- A private synthetic probe checks both backends, returned stress, punctuation
  and contextual complete numbers. Synthetic proposals may be logged only for
  explicitly requested diagnostic tests; ordinary book content stays out of logs.

Source comparison: https://github.com/davnozdu/autoresponder-app/blob/main/app/src/main/java/com/davnozdu/autoresponder/llm/LocalTextModel.kt
