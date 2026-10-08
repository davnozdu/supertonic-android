# -*- coding: utf-8 -*-
"""Russian proper-name stress/ё dictionary for MyTTS, from Wiktionary via kaikki.org (CC BY-SA 4.0).

Input:  https://kaikki.org/dictionary/Russian/pos-name/kaikki.org-dictionary-Russian-by-pos-name.jsonl
Output: app/src/main/assets/names_ru.tsv  ("key<TAB>form": key = lowercase, no stress, ё→е; form = lowercase with
        U+0301 and ё). All declension forms of given names, surnames and place names.
Rules:  monosyllables carry no mark; a key with two different stress positions is dropped (Укра́ина/Украи́на — the LLM
        and Silero decide those); between е and ё the ё form wins; pre-reform spellings (ъ, ѣ, і) are skipped.
Usage:  python3 -I tools/names/build_names.py names.jsonl app/src/main/assets/names_ru.tsv
"""
import collections
import json
import re
import sys

ACUTE = "́"
VOWELS = "аеёиоуыэюя"
CYR = re.compile(r"[А-ЯЁа-яё́-]+")


def vowels(w):
    return sum(c in VOWELS for c in w)


def stress_index(form):
    """Ordinal of the stressed vowel: the acute mark, else the ё, else None."""
    if ACUTE in form:
        return vowels(form[:form.index(ACUTE)])
    if "ё" in form:
        return vowels(form[:form.index("ё") + 1])
    return None


def main(src, dst):
    seen = collections.defaultdict(set)
    for line in open(src, encoding="utf-8"):
        entry = json.loads(line)
        forms = [entry.get("word", "")] + [f.get("form", "") for f in entry.get("forms", [])
                                          if "romanization" not in f.get("tags", []) and "table-tags" not in f.get("tags", [])]
        for form in forms:
            if not form or not CYR.fullmatch(form) or not form[0].isupper() or re.search("[ъѣі]$|ѣ|і", form.lower()):
                continue
            low = form.lower()
            if vowels(low) < 2:
                low = low.replace(ACUTE, "")
            elif ACUTE not in low and "ё" not in low:
                continue  # no stress information
            if low.count(ACUTE) > 1 or "-" in low and low.count(ACUTE) != 1:
                continue
            seen[low.replace(ACUTE, "").replace("ё", "е")].add(low)
    out = {}
    for key, variants in seen.items():
        if vowels(key) < 2:
            yo = [v for v in variants if "ё" in v]
            if yo:
                out[key] = yo[0]
            continue
        with_yo = [v for v in variants if "ё" in v]
        pool = with_yo or list(variants)
        if len({stress_index(v) for v in pool}) == 1:
            out[key] = sorted(pool)[0]
    with open(dst, "w", encoding="utf-8") as f:
        f.write("# Russian proper names: stress and ё. Source: Wiktionary (en) via kaikki.org, CC BY-SA 4.0.\n")
        for key in sorted(out):
            if out[key] != key:
                f.write(f"{key}\t{out[key]}\n")
    print(f"{len(out)} keys -> {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
