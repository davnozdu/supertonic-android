#!/usr/bin/env python3
"""
Build a Lexicon-importable accent dictionary from ruaccent's accents.json.

Usage:
    python3 build_lite.py [MAX_WORD_LEN]   # default 9

Reads /tmp/ruaccent/accents.json and /tmp/ruaccent/omographs.json (download
the .gz files from https://huggingface.co/ruaccent/accentuator/tree/main/dictionary
and gunzip them first).

Writes russian_accents.json (or russian_accents_compact.json for MAX_LEN==8)
next to this script.
"""

import json
import os
import sys

ACUTE = "́"  # combining acute accent, placed AFTER the stressed vowel

def convert(val: str) -> str:
    """Move ruaccent's '+' from before the vowel to a combining acute after it."""
    out = []
    i = 0
    while i < len(val):
        c = val[i]
        if c == "+" and i + 1 < len(val):
            out.append(val[i + 1])
            out.append(ACUTE)
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def main(max_len: int) -> None:
    src = "/tmp/ruaccent/accents.json"
    omo = "/tmp/ruaccent/omographs.json"
    if not os.path.exists(src) or not os.path.exists(omo):
        sys.exit(
            "Missing /tmp/ruaccent/accents.json or omographs.json — see README "
            "for the curl commands that fetch them from Hugging Face."
        )

    with open(src, encoding="utf-8") as f:
        accents = json.load(f)
    with open(omo, encoding="utf-8") as f:
        omographs = json.load(f)

    result = {}
    for key, val in accents.items():
        if len(key) > max_len:
            continue
        if "+" not in val:
            continue
        # Homographs are deliberately skipped — feeding the model one
        # deterministic stress for words like "замок" or "лиса" was worse
        # than letting it pick from context.
        if key in omographs:
            continue
        result[key] = convert(val)

    out_name = "russian_accents.json" if max_len == 9 else "russian_accents_compact.json"
    out_path = os.path.join(os.path.dirname(__file__) or ".", out_name)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"))
    sz = os.path.getsize(out_path) / 1024 / 1024
    print(f"{out_name}: {len(result):,} entries, {sz:.1f} MB")


if __name__ == "__main__":
    max_len = int(sys.argv[1]) if len(sys.argv) > 1 else 9
    main(max_len)
