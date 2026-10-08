# Score chapter-1 probe output on words whose stress is certain (every occurrence counts).
import json, re, sys
A = "́"
KEY = {  # bare lowercase -> correct form
    "оттепель": "о+ттепель", "нахальною": "наха+льною", "субъекте": "субъе+кте", "большею": "бо+льшею",
    "частию": "ча+стию", "развитую": "ра+звитую", "щеками": "щека+ми", "слое": "сло+е",
    "дедов": "де+дов", "подноготную": "подного+тную", "тотчас": "то+тчас", "дальнейшей": "дальне+йшей",
    "пытливо": "пытли+во", "крыму": "крыму+", "спорить": "спо+рить", "досыта": "до+сыта",
    "востренькою": "во+стренькою", "прихвостнями": "прихво+стнями", "ободранными": "ободра+нными",
    "глаза": "глаза+", "мышкин": "мы+шкин", "рогожин": "рого+жин", "павлищев": "павли+щев",
    "петербурга": "петербу+рга", "швейцарии": "швейца+рии", "николаевич": "никола+евич",
}
CONTEXT = [  # (context regex on the unmarked text, word, correct form) for homographs
    (r"франкировку письма", "письма", "письма+"), (r"начал, наконец", "начал", "на+чал"), (r"начал было", "начал", "на+чал"),
]
data = json.load(open(sys.argv[1], encoding="utf-8"))
text = "\n".join(r["text"] for r in data)
plain = text.replace(A, "")
ok = total = 0
for m in re.finditer("[А-Яа-яЁё́]+", text):
    w = m.group(); b = w.replace(A, "").lower()
    if b in KEY:
        total += 1
        if w.lower() == KEY[b].replace("+", A): ok += 1
        else: print(f"  MISS {w} (want {KEY[b].replace('+', A)})")
for ctx, word, want in CONTEXT:
    for m in re.finditer(ctx, plain):
        # locate the same span in the marked text by counting words before it
        n = len(re.findall("[А-Яа-яЁё]+", plain[:m.start()]))
        n += re.findall("[А-Яа-яЁё]+", ctx).index(word)
        w = re.findall("[А-Яа-яЁё́]+", text)[n]
        total += 1
        if w.lower() == want.replace("+", A): ok += 1
        else: print(f"  MISS {w} (want {want.replace('+', A)}) in «{ctx}»")
print(f"key words correct {ok}/{total}")
