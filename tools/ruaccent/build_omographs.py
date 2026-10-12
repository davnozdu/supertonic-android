# Builds app/src/main/assets/ruaccent_omographs.tsv.gz from RUAccent's dictionary/omographs.json.gz
# (https://huggingface.co/ruaccent/accentuator, MIT; data derived from Zaliznyak's grammar dictionary).
# Line: word <TAB> stressed-vowel ordinals of its variants, 1-based ("замок\t1,2").
# Usage: python3 -I build_omographs.py omographs.json.gz out.tsv.gz
import gzip, json, sys
V = "аеёиоуыэюя"
def ordinal(v):
    i = v.find("+")
    return None if i < 0 or i + 1 >= len(v) or v[i + 1] not in V else sum(c in V for c in v[:i]) + 1
data = json.load(gzip.open(sys.argv[1]))
lines = []
for word, variants in sorted(data.items()):
    ords = sorted({o for o in map(ordinal, variants) if o})
    if len(ords) >= 2: lines.append(f"{word}\t{','.join(map(str, ords))}")
with gzip.GzipFile(sys.argv[2], "wb", mtime=0) as f: f.write(("\n".join(lines) + "\n").encode())
print(len(data), "homographs,", len(lines), "with two or more stress variants")
