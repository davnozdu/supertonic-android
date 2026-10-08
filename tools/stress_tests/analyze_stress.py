# Summarize stress-probe-out.json: coverage, providers, names, inconsistent words.
import json, re, sys, collections, unicodedata

ACUTE = "́"
V = "аеёиоуыэюяАЕЁИОУЫЭЮЯ"
WORD = re.compile("[А-Яа-яЁё́]+")
data = json.load(open(sys.argv[1], encoding="utf-8"))

def bare(w): return w.replace(ACUTE, "")
def stressed(w): return ACUTE in w or "ё" in w.lower()
def sentence_start(t, i):
    j = i - 1
    while j >= 0 and (t[j].isspace() or t[j] in "«»\"„“”'()[]—–-"):
        if t[j] == "\n": return True
        j -= 1
    return j < 0 or t[j] in ".!?…:;"

total = marked = 0
providers = collections.Counter()
missing = collections.Counter()
forms = collections.defaultdict(collections.Counter)  # bare lower -> stressed variant counts
names = collections.defaultdict(collections.Counter)
for rec in data:
    providers[(rec["provider"], rec["fallback"])] += 1
    t = unicodedata.normalize("NFC", rec["text"]).replace("е" + ACUTE, "е" + ACUTE)
    t = rec["text"]
    for m in WORD.finditer(t):
        w = m.group()
        if sum(c in V for c in bare(w)) < 2: continue
        total += 1
        if stressed(w): marked += 1
        else: missing[bare(w).lower()] += 1
        forms[bare(w).lower()][w.lower()] += 1
        if w[0].isupper() and not sentence_start(t, m.start()):
            names[bare(w)][w] += 1

print("paragraphs", len(data), "providers", dict(providers))
print(f"multi-syllable words {total}, stressed {marked} ({100*marked/max(total,1):.1f}%)")
print("unstressed (top 60):", ", ".join(f"{w}×{n}" for w, n in missing.most_common(60)))
print("\nNAMES (mid-sentence capitalized):")
for b, c in sorted(names.items()): print(" ", b, dict(c))
print("\nWORDS WITH MORE THAN ONE STRESS VARIANT:")
for b, c in sorted(forms.items()):
    variants = {k: v for k, v in c.items() if ACUTE in k or "ё" in k}
    if len(variants) > 1: print(" ", b, variants)
if len(sys.argv) > 2:
    with open(sys.argv[2], "w", encoding="utf-8") as f:
        for rec in data: f.write(f"[{rec['provider']}{' FALLBACK' if rec['fallback'] else ''}] {rec['text']}\n\n")
