# Pre-built accent dictionaries

Ready-to-import dictionaries for the **Lexicon → Import accent dictionary…** flow in the app. They mark Russian stress with the combining acute accent `U+0301` after each stressed vowel, e.g. `"москва": "москва́"`. Whether the TTS model actually honours those marks depends on its training data — verify with a short phrase before trusting the dictionary for long-form synthesis.

## Download

Attached to release [`v3.1.1`](https://github.com/davnozdu/supertonic-android/releases/tag/v3.1.1):

| File | Size | Entries | Word length ≤ | When to use |
|---|---|---|---|---|
| [`russian_accents.json`](https://github.com/davnozdu/supertonic-android/releases/download/v3.1.1/russian_accents.json) | 36 MB | 961 968 | 9 chars | **Recommended.** Phone with ≥ 4 GB RAM. |
| [`russian_accents_compact.json`](https://github.com/davnozdu/supertonic-android/releases/download/v3.1.1/russian_accents_compact.json) | 21 MB | 615 365 | 8 chars | Low-RAM / older devices. |

## How to import

1. Download the JSON to the phone (browser, AirDrop alternatives, or `adb push`).
2. Supertonic TTS → menu → **Lexicon** → menu (`⋮`) → **Import accent dictionary…**
3. Pick the file. A dialog reports the loaded entry count.
4. From this point every synthesis pass swaps matching words for their stressed form before the text reaches the model.

User-edited Lexicon rules **always win** over the imported dictionary, so it's safe to override a specific word locally.

## What was filtered out

| Filter | Reason |
|---|---|
| Words longer than 8–9 characters (depending on file) | Most are rare inflected forms (plural genitive / instrumental of low-frequency nouns). Cutting them roughly halves the file without proportional loss of coverage. |
| Single-syllable / no-stress entries | The model needs no hint for those; they only bulk up the file. |
| ~13 000 known homographs (`замок`, `мука`, `атлас`, `орган`, `пора`, `пили`, `лиса`, etc.) | The source dictionary lists multiple valid stresses for each, with no context. Picking one deterministically gave wrong answers more often than letting the TTS choose. For these words the model decides on its own. |

If you want everything — including all 3.19 M inflected forms and ML-based homograph resolution — use the upstream [`ruaccent`](https://github.com/Den4ikAI/ruaccent) Python package locally before synthesis instead of relying on the in-app dictionary.

## Source & licensing

Derived from the [`ruaccent/accentuator`](https://huggingface.co/ruaccent/accentuator) Hugging Face repository — specifically `dictionary/accents.json.gz` and `dictionary/omographs.json.gz`. That data is itself a derivative of the **A.A. Zaliznyak Grammar Dictionary of Russian** (`odict.ru`). Use accordingly — the underlying work is freely available for non-commercial use; for commercial deployment check the source licences directly.

The conversion logic is in the build script kept alongside this README — it walks every entry, moves the ruaccent-style `+` from before the stressed vowel to a combining acute (`U+0301`) after it.

## Rebuilding the dictionaries yourself

```bash
mkdir -p /tmp/ruaccent && cd /tmp/ruaccent
curl -sSLO https://huggingface.co/ruaccent/accentuator/resolve/main/dictionary/accents.json.gz
curl -sSLO https://huggingface.co/ruaccent/accentuator/resolve/main/dictionary/omographs.json.gz
gunzip -k accents.json.gz omographs.json.gz
python3 build_lite.py 9     # produces russian_accents.json
python3 build_lite.py 8     # produces russian_accents_compact.json
```

See [`build_lite.py`](build_lite.py) for the converter.

## Other languages

Only Russian is provided so far. The Supertonic 3 model accepts 31 languages; if you have an accent / stress dictionary for any of them in the `{"word": "wórd"}` shape (with `U+0301`-style diacritics where the language uses stress marks), import is the same and works without code changes. Pull requests with additional dictionaries welcome.
