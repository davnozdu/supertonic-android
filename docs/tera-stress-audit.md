# Tera Russian stress audit (2026-10-01)

The pinned Tera dictionary index contains 3,194,879 entries. A complete scan found no key/value spelling mismatches, invalid `+` positions, or unsorted keys. This verifies the binary conversion; it does not prove that every dictionary stress is linguistically correct in every context.

Upstream `omographs.json.gz` lists 19,741 ambiguous words. Of those, 18,007 entries in the main index carry a fixed stress. Examples:

| Word | Index value | Other upstream variant |
|---|---|---|
| светло | св+етло | светл+о |
| готов | г+отов | гот+ов |
| потом | п+отом | пот+ом |
| замок | з+амок | зам+ок |
| мука | м+ука | мук+а |
| стоит | ст+оит | сто+ит |

The app now leaves these words unmarked instead of forcing a dictionary variant. Explicit `+` and U+0301 markers, including user lexicon corrections, still win. The 637 ambiguous е/ё replacements are likewise excluded from automatic replacement. Known unambiguous words continue to use the mmap index.

The exclusion lists in `app/src/main/assets/tera_ambiguous_stress.txt` and `tera_ambiguous_yo.txt` come from TeraTTSv2 revision `f05ea799094571a3553904a555df3834fb0b963b`, `ruaccent/dictionary/omographs.json.gz` and `yo_homographs.json.gz`. `коса` is included following the upstream runtime override. See `TERA_RUACCENT_NOTICE.txt` for attribution.

Regression checks cover punctuation, ambiguous stress, explicit user stress, and ambiguous е/ё. The contextual correction “по-прежнему светл+о” can be added to the user lexicon without changing every occurrence of “светло”.
