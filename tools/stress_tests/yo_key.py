# ё restoration: "write" makes the probe input (every ё -> е) from yo_orlovy_key.txt; "score" compares a probe output
# with the key word by word: restored ё, missed ё, ё added where the book has е.
import json, re, sys
A = "́"
key = [p.strip() for p in open(sys.argv[2] if sys.argv[1] == "write" else "yo_orlovy_key.txt", encoding="utf-8").read().split("\n\n") if p.strip()]
if sys.argv[1] == "write":
    open(sys.argv[3], "w", encoding="utf-8").write("\n\n".join(p.replace("ё", "е").replace("Ё", "Е") for p in key) + "\n")
    sys.exit()
out = json.load(open(sys.argv[2], encoding="utf-8"))
W = re.compile("[А-Яа-яЁё" + A + "]+")
ok = miss = extra = 0
for k, r in zip(key, out):
    a = W.findall(k); b = [w.replace(A, "") for w in W.findall(r["text"])]
    if len(a) != len(b): print("  misaligned:", k[:60]); continue
    for x, y in zip(a, b):
        hx = any(c in "ёЁ" for c in x); hy = any(c in "ёЁ" for c in y)
        if hx and x.lower() == y.lower(): ok += 1
        elif hx: miss += 1; print(f"  MISS {x} got {y}")
        elif hy: extra += 1; print(f"  EXTRA {y} (book {x})")
print(f"yo restored {ok}/{ok + miss}, extra {extra}")
