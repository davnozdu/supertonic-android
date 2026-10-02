# Beta 15: Moon+ Reader live queue corrections

## Observed on the device with Beta 14

Moon+ Reader was the foreground client (`com.flyersoft.moonreaderp/.ActivityTxt`). Gemini prepared 15/19 fragments of a 3,968-character batch in 7,451 ms; Ollama later prepared 25/27 fragments of a 3,877-character batch in 7,090 ms. The reader used both successful cloud deliveries and dictionary fallback after the 1,500 ms waiting limit. A repeated two-fragment request hit the RAM cache and first audio arrived after 316 ms.

Partial rejection caused the entire batch to be retried with local Gemma, which timed out after approximately 45 seconds and blocked the single preparation worker. Thus the active session genuinely used LLM, but not for every fragment.

## Fixes

- Retain validated fragments from partially successful batches in the exact-context cache. Retry only missing fragments plus immediate neighbours, within the provider's limit.
- Give the chosen cloud one repair attempt on unresolved fragments before trying local fallback. A successful neighbour is never discarded or delivered twice.
- Limit local generation during live reading to 12 seconds after conversation creation (model initialization is separate), and bound local retry inputs to 1,600 characters. The manual text test retains its 45-second generation deadline. Local-only queued batches are limited to approximately 1,000 characters.
- Log rejection categories and requested/remaining counts without book contents or keys. Examples: changed word count, altered number, rewritten word, lost ё and missing stress.
- Permit numeric gender correction only in the trailing units word, preserving fixed agreement with тысяча/миллион/миллиард.
- Clarify contextual ё instructions with paired present/future examples. Beta 14's controlled Gemini test had correctly handled count gender but chose present-tense узнаёт for a sentence with завтра; that is a quality error, not proof of correct ё restoration in every context.

The normal 1,500 ms reading deadline remains: an unavailable/unready LLM may still cause dictionary fallback. These changes remove avoidable queue stalls; they do not claim that every fragment always waits for neural processing.

Local verification: 36 pure Kotlin JUnit tests passed, including partial cache merge and bounded neighbour selection. Android tests, release shrinker/JNI checks and signing run on GitHub before installation.
