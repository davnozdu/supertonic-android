import sys, json, re
from silero_stress import load_accentor
acc = load_accentor()
out = {}
for line in open(sys.argv[1], encoding='utf-8'):
    for w in line.strip().split('|'):
        bare = w.replace('́', '')
        for frame in ('Вчера пришёл {}.', 'Я говорил про {}.'):
            r = acc(frame.format(bare))
            m = [t for t in r.split() if t.replace('+', '').lower().replace('ё','е').strip('.') == bare.lower().replace('ё','е')]
            out.setdefault(bare, []).append(m[0].strip('.') if m else r)
json.dump(out, open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
