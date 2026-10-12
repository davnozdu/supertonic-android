#!/usr/bin/env python3
"""MyTTS · подготовка книги к озвучке голосами персонажей.

Одна команда — EPUB или FB2 на входе, файл .mytts-book на выходе (импорт в MyTTS: настройки LLM → мультиголос →
«Книги с голосами персонажей» → «Загрузить файл книги»):

  python book_characters.py process book.epub                      # Ollama Cloud, ключ OLLAMA_API_KEY
  python book_characters.py process book.epub --provider deepseek  # API DeepSeek, ключ DEEPSEEK_API_KEY

Скрипт сам находит имена, фамилии, отчества, прозвища и обращения (морфология pymorphy3 и статистика книги),
LLM только сопоставляет их: какие формы — один персонаж. Сомнительное уходит в «прочие». Роман или сборник
рассказов определяется по оглавлению. В файл .mytts-book попадают персонажи и отпечатки предложений, текста
книги в нём нет. Отдельные шаги: extract → llm → apply → voices → export (см. README.md).
"""
from __future__ import annotations

import argparse
import collections
import concurrent.futures
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from argparse import Namespace
import zipfile
from dataclasses import dataclass, field
from pathlib import PurePosixPath

try:
    import pymorphy3
    from bs4 import BeautifulSoup
except ImportError:  # pragma: no cover
    sys.exit("Нужны pymorphy3 и beautifulsoup4: pip install pymorphy3 pymorphy3-dicts-ru beautifulsoup4")

ACUTE = "́"
WORD = re.compile(r"[А-ЯЁа-яё]+(?:-[А-ЯЁа-яё]+)*")
SENTENCE_END = ".!?…"
OPENERS = " \t«\"„“(—–-'"
DASH = re.compile(r"\s[—–]\s")
BLOCKS = ["p", "h1", "h2", "h3", "h4", "h5", "h6", "li"]
# Обращения, которые сами указывают на персонажа («сказал князь») или стоят перед именем («генерал Иволгин»).
TITLES = {
    "князь", "княгиня", "княжна", "граф", "графиня", "барон", "баронесса", "генерал", "генеральша",
    "полковник", "капитан", "поручик", "подпоручик", "майор", "господин", "госпожа", "мадам", "мадемуазель",
    "мсье", "сударь", "сударыня", "барыня", "барин", "доктор", "профессор", "чиновник", "купец", "купчиха",
    # переводная и фэнтезийная проза: «леди Калли», «сэр Ивенс», «мистер Смит», «фрау Мюллер»
    "леди", "лорд", "сэр", "мистер", "миссис", "мисс", "фрау", "герр", "сеньор", "сеньора", "синьор", "синьора",
    "пан", "пани", "месье", "мадемуазель", "миледи", "милорд",
}
NOT_DESCRIPTORS = {"другой", "первый", "второй", "последний", "остальной", "один", "оба", "голос", "кто", "никто", "всякий"}
NOT_PEOPLE = {"бог", "господь", "господи", "христос", "богородица", "аллах", "сатана", "иисус"}
CASE_ENDINGS = ("ами", "ями", "ому", "ему", "ыми", "ими", "ою", "ею", "ой", "ей", "ым", "им", "ом", "ем", "ых", "их",
                "а", "у", "е", "ы", "и", "ю", "я")
# Сборник: доля упоминаний главных персонажей в их «домашнем» разделе не ниже этой.
COLLECTION_CONCENTRATION = 0.75
# Сколько отрывков по всей книге проверяет LLM для обращения или голой фамилии.
LABEL_SAMPLES = 16
# Обращение в романе: отрывков из каждой главы (решение по главе) и сколько отрывков в одном запросе.
LABEL_PER_CHAPTER = 3
LABEL_PER_BUSY_CHAPTER = 5
# Порог главы, когда основной разбор уже отдал обращение этому человеку и ни один отрывок главы не называет
# другого персонажа: два независимых признака согласны.
AGREED_DOMINANCE = 0.6
LABEL_BATCH = 40
LABEL_THINK = False
# Решение по одной главе: не меньше стольких понятных ответов.
MIN_CHAPTER_ANSWERS = 2
# Меньше понятных ответов по отрывкам — решения по обращению нет (оно в «прочих»).
MIN_LABEL_ANSWERS = 3
SINGLE_SURNAME = re.compile(r"^[а-яё-]{3,}(?:ов|ев|ёв|ин|ын)$")
FAMILY_SURNAME = re.compile(r"(?<=[а-яё]{2})(ов|ев|ёв|ин|ын)(?:ы|ых|ыми)$")
CASES = ("nomn", "gent", "datv", "accs", "ablt", "loct", "voct", "gen2", "acc2", "loc2")


# ---------------------------------------------------------------- книга

@dataclass
class Book:
    title: str
    author: str
    sections: list            # [{"id": "s1", "title": "Старшая сестра"}]
    paragraphs: list          # [(номер раздела, текст)]
    notes: frozenset = frozenset()  # номера абзацев-сносок: в отпечатках есть, в разборе персонажей — нет
    analysis: dict = field(default_factory=dict)  # номер абзаца → текст без значков сносок («Рогожин¹»)


def read_epub(path: str, nested: bool | int = False) -> Book:
    """Абзацы в порядке чтения. Разделы — верхний уровень оглавления (в сборнике вложенные пункты — части
    рассказа); nested — все пункты оглавления, то есть главы романа («ЧАСТЬ ПЕРВАЯ. I.»)."""
    with zipfile.ZipFile(path) as z:
        container = ET.fromstring(z.read("META-INF/container.xml"))
        opf_path = next(e.get("full-path") for e in container.iter() if e.tag.endswith("rootfile"))
        opf = ET.fromstring(z.read(opf_path))
        base = PurePosixPath(opf_path).parent
        title = next((e.text for e in opf.iter() if e.tag.endswith("}title") and e.text), PurePosixPath(path).stem)
        author = next((e.text for e in opf.iter() if e.tag.endswith("}creator") and e.text), "")
        items = {e.get("id"): e for e in opf.iter() if e.tag.endswith("}item")}
        spine = [items[e.get("idref")].get("href") for e in opf.iter() if e.tag.endswith("}itemref") and e.get("idref") in items]
        toc = toc_entries(z, base, items, opf, nested)
        # Пункт оглавления: файл → [(якорь или None, номер раздела)]
        starts: dict[str, list] = collections.defaultdict(list)
        sections = []
        for label, href in toc:
            file, _, anchor = href.partition("#")
            starts[normalize_href(file)].append((anchor or None, len(sections)))
            sections.append({"id": f"s{len(sections) + 1}", "title": label})
        if not sections:  # без оглавления: раздел = файл
            for href in spine:
                starts[normalize_href(href)].append((None, len(sections)))
                sections.append({"id": f"s{len(sections) + 1}", "title": PurePosixPath(href).stem})
        paragraphs: list[tuple[int, str]] = []
        notes: set[int] = set()
        analysis: dict[int, str] = {}
        aside = {items[e.get("idref")].get("href") for e in opf.iter()
                 if e.tag.endswith("}itemref") and e.get("idref") in items and e.get("linear") == "no"}
        current = 0
        for href in spine:
            if not href.endswith((".xhtml", ".html", ".htm")):
                continue
            pending = dict(starts.get(normalize_href(href), []))
            if None in pending:
                current = pending.pop(None)
            soup = BeautifulSoup(z.read(str(base / href)).decode("utf-8", "replace"), "html.parser")
            for tag in soup.find_all(True):
                if tag.get("id") in pending:
                    current = pending.pop(tag.get("id"))
                if tag.name in BLOCKS and not tag.find(BLOCKS):
                    text = clean(tag.get_text(" "))
                    if text:
                        bare = text_without_noterefs(tag)
                        if bare != text:
                            analysis[len(paragraphs)] = bare
                        if href in aside or NOTES_TITLE.search(sections[current]["title"] if sections else "") or is_note(tag):
                            notes.add(len(paragraphs))
                        paragraphs.append((current, text))
    used = sorted({s for s, _ in paragraphs})
    renumber = {old: new for new, old in enumerate(used)}
    sections = [dict(sections[old], id=f"s{renumber[old] + 1}") for old in used]
    return Book(title, clean(author), sections, [(renumber[s], t) for s, t in paragraphs], frozenset(notes), analysis)


NOTEREF_TEXT = re.compile(r"^\s*[\[(]?\s*(?:\d{1,3}|[*†‡]+|[ivx]{1,4})\s*[\])]?\s*$", re.IGNORECASE)


def text_without_noterefs(tag) -> str:
    """Текст абзаца без значков сносок: <a epub:type="noteref">, <sup>1</sup>, <a href="#n1">[1]</a>."""
    def skip(node) -> bool:
        while node is not None and node is not tag:
            name = getattr(node, "name", None)
            if name in ("sup", "a"):
                kind = (node.get("epub:type") or "") + " " + (node.get("role") or "")
                if "noteref" in kind or NOTEREF_TEXT.match(node.get_text()):
                    return True
            node = node.parent
        return False
    return clean(" ".join(str(x) for x in tag.find_all(string=True) if not skip(x.parent)))


# Раздел целиком из сносок: последний уровень названия — только это слово («Примечания», «ЧАСТЬ I. Сноски»).
NOTES_TITLE = re.compile(r"(?:^|[\s.])(?:примечания|примечание|сноски|комментарии|notes|endnotes|footnotes)\.?\s*$", re.IGNORECASE)
NOTE_CLASS = re.compile(r"(?:^|[\s_-])(?:foot|end|rear)?notes?(?:$|[\s_-])|snoska|sноск|primech|komment", re.IGNORECASE)


def is_note(tag) -> bool:
    """Абзац внутри сноски: <aside>, epub:type="footnote|endnote|rearnote|note", класс note/footnote/snoska."""
    for node in [tag, *tag.parents]:
        if getattr(node, "name", None) is None:
            continue
        if node.name == "aside":
            return True
        kind = (node.get("epub:type") or node.get("type") or "")
        if any(k in kind.split() for k in ("footnote", "endnote", "rearnote", "note", "footnotes", "endnotes", "rearnotes")):
            return True
        classes = node.get("class") or []
        if any(NOTE_CLASS.search(c) for c in (classes if isinstance(classes, list) else [classes])):
            return True
    return False


HEADING = re.compile(r"^(?:(?:глава|часть|книга|chapter|part)\s+(?:[0-9]{1,3}|[ivxlcdm]{1,7}|[а-яё-]{3,20})|"
                     r"[ivxlcdm]{1,7}|[0-9]{1,3})\.?$", re.IGNORECASE)


def stories(path: str, book: Book) -> Book:
    """Рассказы сборника. Оглавление бывает смешанным: часть «Люди» делится на рассказы, а рассказ «Эррата»
    — на главы. Раздел делится на подразделы следующего уровня, только если у каждого подраздела свои герои
    (как у рассказов сборника); если одни и те же герои проходят через подразделы — это главы одного рассказа."""
    for depth in range(2, 6):
        deeper = read_book(path, nested=depth)
        if len(deeper.paragraphs) != len(book.paragraphs) or len(deeper.sections) <= len(book.sections):
            break
        found = Extractor().run(normalized(deeper), per_section=False)
        named = [c for c in found.values() if c.kind == "name"]
        children: dict[int, set] = collections.defaultdict(set)
        for (unit, _), (child, _) in zip(book.paragraphs, deeper.paragraphs):
            children[unit].add(child)
        split = {}
        for unit, kids in children.items():
            heroes = sorted(((sum(c.sections[k] for k in kids), c) for c in named), key=lambda x: -x[0])[:10]
            heroes = [(n, c) for n, c in heroes if n >= 5]
            total = sum(n for n, _ in heroes)
            home = sum(max(c.sections[k] for k in kids) for _, c in heroes)
            split[unit] = len(kids) > 1 and len(heroes) >= 2 and home >= COLLECTION_CONCENTRATION * total
        if not any(split.values()):
            break
        keys, sections, paragraphs = {}, [], []
        for (unit, text), (child, _) in zip(book.paragraphs, deeper.paragraphs):
            key = ("child", child) if split[unit] else ("unit", unit)
            if key not in keys:
                keys[key] = len(sections)
                title = deeper.sections[child]["title"] if split[unit] else book.sections[unit]["title"]
                sections.append({"id": f"s{len(sections) + 1}", "title": title})
            paragraphs.append((keys[key], text))
        book = Book(book.title, book.author, sections, paragraphs, book.notes, book.analysis)
    return merge_tiny_sections(book)  # заголовок части («Люди») — к первому рассказу


def chapters(path: str) -> Book:
    """Главы романа для решений по главам: пункты оглавления всех уровней; если их нет или он один —
    заголовки в тексте («Глава 5», «XII.», «12.» отдельной строкой); иначе книга как есть.
    Граница главы всегда совпадает с началом абзаца-заголовка или пункта оглавления — книга не режется
    механически по объёму."""
    book = read_book(path, nested=True)
    if len(book.sections) >= 3:
        return merge_tiny_sections(book)
    marks = [i for i, (_, t) in enumerate(book.paragraphs) if len(t) <= 40 and HEADING.match(t.strip())]
    gaps = [b - a for a, b in zip(marks, marks[1:])]
    if len(marks) < 3 or sorted(gaps)[len(gaps) // 2] < 10:
        return book  # нет ясных заголовков (или это нумерованный список) — не делим
    sections, paragraphs, current = [], [], -1
    starts = set(marks)
    previous_section = None
    for i, (old, text) in enumerate(book.paragraphs):
        if i in starts or old != previous_section or current < 0:
            title = text if i in starts else book.sections[old]["title"]
            sections.append({"id": f"s{len(sections) + 1}", "title": title})
            current = len(sections) - 1
        previous_section = old
        paragraphs.append((current, text))
    return merge_tiny_sections(Book(book.title, book.author, sections, paragraphs, book.notes, book.analysis))


def merge_tiny_sections(book: Book, smallest: int = 3) -> Book:
    """Раздел из одного-двух абзацев («ЧАСТЬ ПЕРВАЯ.», титул) — к следующему: главы — это текст, а не заголовки."""
    size = collections.Counter(s for s, _ in book.paragraphs)
    order = sorted(size)
    target, carry = {}, []
    for s in order:
        carry.append(s)
        if size[s] >= smallest:
            for x in carry:
                target[x] = s
            carry = []
    for x in carry:  # хвост из мелких — к последнему настоящему
        target[x] = max(target.values(), default=x)
    used = sorted(set(target.values()))
    renumber = {old: new for new, old in enumerate(used)}
    sections = [dict(book.sections[old], id=f"s{renumber[old] + 1}") for old in used]
    return Book(book.title, book.author, sections, [(renumber[target[s]], t) for s, t in book.paragraphs], book.notes, book.analysis)


def read_book(path: str, nested: bool | int = False) -> Book:
    """EPUB или FB2 (в том числе .fb2.zip). nested — разделы по главам (все уровни оглавления)."""
    low = path.lower()
    if low.endswith((".fb2", ".fb2.zip")):
        return read_fb2(path, nested)
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            if "META-INF/container.xml" not in z.namelist() and any(n.lower().endswith(".fb2") for n in z.namelist()):
                return read_fb2(path, nested)
    return read_epub(path, nested)


def read_fb2(path: str, nested: bool | int = False) -> Book:
    """FB2: разделы — секции верхнего уровня основного <body> (как верхний уровень оглавления EPUB; nested —
    вложенные секции, то есть главы),
    абзацы — <p>, строки стихов <v>, подзаголовки и подписи; сноски (<body name="notes">) не читаются."""
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            data = z.read(next(n for n in z.namelist() if n.lower().endswith(".fb2")))
    else:
        with open(path, "rb") as f:
            data = f.read()
    root = ET.fromstring(data)  # кодировку (часто windows-1251) берёт из заголовка XML

    def local(e) -> str:
        return e.tag.rsplit("}", 1)[-1]

    def text(e) -> str:
        return clean(" ".join(e.itertext()))

    info = next((e for e in root.iter() if local(e) == "title-info"), None)
    title = PurePosixPath(path).stem
    author = ""
    if info is not None:
        title = next((text(e) for e in info if local(e) == "book-title" and text(e)), title)
        first = next((e for e in info if local(e) == "author"), None)
        if first is not None:
            author = " ".join(text(e) for e in first if local(e) in ("first-name", "middle-name", "last-name") and text(e))
    bodies = [e for e in root if local(e) == "body" and e.get("name") not in ("notes", "comments", "footnotes")]
    depth = 99 if nested is True else int(nested) if nested else 1
    sections: list = []
    paragraphs: list[tuple[int, str]] = []
    leaf = {"p", "v", "subtitle", "text-author"}

    def open_section(e, path) -> tuple[int, list]:
        heading = next((text(t) for t in e if local(t) == "title"), "")
        label = " ".join(path + [heading]) if heading else (" ".join(path) or f"Раздел {len(sections) + 1}")
        sections.append({"id": f"s{len(sections) + 1}", "title": label})
        return len(sections) - 1, path + [heading] if heading else path

    def walk(e, section: int, path: list) -> None:
        if local(e) in leaf:
            line = text(e)
            if line:
                paragraphs.append((section, line))
            return
        for child in e:
            if local(child) == "section" and len(path) < depth:
                inner, inner_path = open_section(child, path)
                walk(child, inner, inner_path)
                # текст родительской секции после вложенной (редко) — снова к родителю
            else:
                walk(child, section, path)

    for body in bodies:
        for child in body:
            if local(child) == "section":
                index, path = open_section(child, [])
                walk(child, index, path)
            else:  # заголовок книги, эпиграф перед первой секцией
                walk(child, max(0, len(sections) - 1), [])
    if not sections:
        sections = [{"id": "s1", "title": title}]
    used = sorted({s for s, _ in paragraphs})
    renumber = {old: new for new, old in enumerate(used)}
    sections = [dict(sections[old], id=f"s{renumber[old] + 1}") for old in used]
    return Book(clean(title), clean(author), sections, [(renumber[s], t) for s, t in paragraphs])


def toc_entries(z, base, items, opf, nested: bool | int = False) -> list[tuple[str, str]]:
    """Пункты оглавления (название, ссылка) по порядку. nested — с вложенными, название с путём:
    «ЧАСТЬ ПЕРВАЯ. I.»; иначе только верхний уровень."""
    depth = 99 if nested is True else int(nested) if nested else 1  # число уровней оглавления
    nav = next((e for e in items.values() if "nav" in (e.get("properties") or "").split()), None)
    if nav is not None:
        soup = BeautifulSoup(z.read(str(base / nav.get("href"))).decode("utf-8", "replace"), "html.parser")
        toc = soup.find("nav", attrs={"epub:type": "toc"}) or soup.find("nav")
        ol = toc.find("ol") if toc else None
        out: list[tuple[str, str]] = []

        def walk_nav(ol, path):
            for li in ol.find_all("li", recursive=False):
                a = li.find("a")
                label = clean(a.get_text(" ")) if a else ""
                if a and a.get("href"):
                    out.append((" ".join(path + [label]), resolve(nav.get("href"), a.get("href"))))
                child = li.find("ol")
                if len(path) + 1 < depth and child:
                    walk_nav(child, path + [label] if label else path)
        if ol:
            walk_nav(ol, [])
            if out:
                return out
    spine = next((e for e in opf.iter() if e.tag.endswith("}spine")), None)
    ncx_id = spine.get("toc") if spine is not None else None
    ncx = items.get(ncx_id) if ncx_id else next((e for e in items.values() if e.get("href", "").endswith(".ncx")), None)
    if ncx is None:
        return []
    root = ET.fromstring(z.read(str(base / ncx.get("href"))))
    nav_map = next((e for e in root.iter() if e.tag.endswith("}navMap")), None)
    out = []

    def walk_ncx(parent, path):
        for point in parent:
            if not point.tag.endswith("}navPoint"):
                continue
            label_e = next((e for e in point if e.tag.endswith("}navLabel")), None)
            label = clean(next((e.text for e in label_e.iter() if e.tag.endswith("}text") and e.text), "")) if label_e is not None else ""
            content = next((e for e in point if e.tag.endswith("}content")), None)
            if content is not None:
                out.append((" ".join(path + [label]), resolve(ncx.get("href"), content.get("src"))))
            if len(path) + 1 < depth:
                walk_ncx(point, path + [label] if label else path)
    if nav_map is not None:
        walk_ncx(nav_map, [])
    return out


def resolve(origin: str, href: str) -> str:
    folder = PurePosixPath(origin).parent
    return str(folder / href) if str(folder) != "." else href


def normalize_href(href: str) -> str:
    return os.path.normpath(href.split("#")[0]).replace("\\", "/")


# ---------------------------------------------------------------- нормализация для разбора
# Только для поиска имён и ремарок; отпечатки для узнавания книги считаются по исходному тексту.
INVISIBLE = re.compile("[\u00ad\u200b-\u200f\u2060\ufeff\u202a-\u202e]")
LATIN_LOOKALIKE = str.maketrans("aeopcyxAEOPCTXKMHB", "аеорсухАЕОРСТХКМНВ")
MIXED_WORD = re.compile(r"[A-Za-zА-ЯЁа-яё]+")
DASH_START = re.compile(r"^\s*(?:--?|[‐‑‒–—―−])\s*")
DASH_INSIDE = re.compile(r"(?<=\S)\s+(?:--?|[‐‒–—―−])\s+|(?<=[,.!?…»\"])(?:--?|[‒–—―−])\s+")
FOOTNOTE = re.compile(r"\[\d{1,3}\]|\{\d{1,3}\}|[¹²³⁰⁴-⁹]+")
DECOR = re.compile(r"[*•§~_#|¤◆◇■□●○★☆►▪︎❖✦✧]+")
QUOTES = str.maketrans({"„": "«", "“": "»", "”": "»", "‟": "«", "‹": "«", "›": "»", '"': "«"})


def normalize_text(text: str) -> str:
    """Механическая очистка перед разбором: тире любого вида («-», «--», «–», «―») → «—», кавычки → «»,
    «...» → «…»; без невидимых символов (мягкий перенос), сносок («[1]», «¹»), звёздочек и декора;
    латинские буквы-двойники внутри русских слов («Pогожин» из распознанного скана) → русские."""
    text = INVISIBLE.sub("", text)
    text = FOOTNOTE.sub("", text)
    text = DECOR.sub(" ", text)
    text = MIXED_WORD.sub(lambda m: m.group().translate(LATIN_LOOKALIKE)
                          if re.search("[А-ЯЁа-яё]", m.group()) and re.search("[A-Za-z]", m.group()) else m.group(), text)
    text = text.replace("...", "…")
    text = DASH_START.sub("— ", text, count=1) if DASH_START.match(text) and len(text) > 2 else text
    text = DASH_INSIDE.sub(" — ", text)
    # Кавычки: открывающая/закрывающая по положению (прямые «"» из плохих конвертеров).
    text = re.sub(r'"(?=\w)', "«", text)
    text = re.sub(r'(?<=\S)"', "»", text)
    text = text.translate(QUOTES)
    text = re.sub(r"\s+([.,!?…;:»)])", r"\1", text)  # пробел на месте убранного значка сноски
    return re.sub(r"\s+", " ", text).strip()


def normalized(book: Book) -> Book:
    """Та же книга (разделы, порядок абзацев) с очищенным для разбора текстом; пустые абзацы остаются пустой
    строкой, чтобы номера абзацев совпадали с исходными."""
    return Book(book.title, book.author, book.sections,
                [(s, "" if i in book.notes else normalize_text(book.analysis.get(i, t))) for i, (s, t) in enumerate(book.paragraphs)],
                book.notes)


def clean(text: str) -> str:
    return re.sub(r"\s+", " ", text.replace("\xa0", " ").replace(ACUTE, "")).strip()


# ---------------------------------------------------------------- отпечатки (формат .mytts-book v1)
# Одинаково в приложении (Kotlin, books/BookFingerprint.kt): предложения по SENTENCES, буквы и цифры в нижнем
# регистре (ё → е, без ударений), первые 48; меньше 24 — не используется; FNV-1a 64 по кодам символов.

SENTENCES = re.compile(r"(?<=[.!?…])\s+")
FNV_OFFSET, FNV_PRIME, MASK64 = 0xcbf29ce484222325, 0x100000001b3, (1 << 64) - 1


def letters(text: str) -> str:
    return "".join(c for c in text.lower().replace("ё", "е").replace(ACUTE, "") if "а" <= c <= "я" or "a" <= c <= "z" or "0" <= c <= "9")


def sentence_fingerprints(text: str) -> list[str]:
    out = []
    for sentence in SENTENCES.split(text):
        key = letters(sentence)[:48]
        if len(key) < 24:
            continue
        h = FNV_OFFSET
        for c in key:
            h = ((h ^ ord(c)) * FNV_PRIME) & MASK64
        out.append(f"{h:016x}")
    return out


def fingerprint(text: str) -> str | None:
    """Первые 80 букв абзаца без регистра, ё, ударений и знаков: так же нормализует читалка."""
    letters = re.sub(r"[^a-zа-я0-9]", "", text.lower().replace("ё", "е").replace(ACUTE, ""))
    return hashlib.sha1(letters[:80].encode()).hexdigest()[:12] if len(letters) >= 24 else None


def sentence_start(text: str, start: int) -> bool:
    i = start - 1
    while i >= 0 and text[i] in OPENERS:
        i -= 1
    return i < 0 or text[i] in SENTENCE_END


# ---------------------------------------------------------------- кандидаты

@dataclass
class Candidate:
    key: str                      # «настасья филипповна»: слова в именительном падеже
    kind: str                     # name / title / family
    scope: int                    # раздел (сборник) или 0 (роман)
    count: int = 0
    speaker: int = 0              # назван в ремарке диалога в именительном («— сказал князь»)
    genders: collections.Counter = field(default_factory=collections.Counter)  # "verb:m", "Patr:f"…
    roles: collections.Counter = field(default_factory=collections.Counter)    # Name / Patr / Surn
    titles: collections.Counter = field(default_factory=collections.Counter)   # «князь» перед «Мышкин»
    forms: collections.Counter = field(default_factory=collections.Counter)    # как написано в книге
    sections: collections.Counter = field(default_factory=collections.Counter)
    examples: list = field(default_factory=list)
    spots: list = field(default_factory=list)   # (абзац, начало, конец) каждого упоминания
    descriptor: bool = False      # «черномазый», «камердинер»: так назван говорящий в ремарке
    speaker_sections: collections.Counter = field(default_factory=collections.Counter)  # реплики по разделам
    together: collections.Counter = field(default_factory=collections.Counter)


@dataclass
class Mention:
    start: int
    end: int
    cand: Candidate
    nominative: bool


class Extractor:
    def __init__(self) -> None:
        self.morph = pymorphy3.MorphAnalyzer()
        self.cache: dict[str, list] = {}
        self.capitalized: collections.Counter = collections.Counter()
        self.lower: collections.Counter = collections.Counter()
        self.capital_inside: collections.Counter = collections.Counter()
        self.normal_inside: collections.Counter = collections.Counter()

    def named(self, word: str) -> list:
        """Разборы как имени/фамилии/отчества в единственном числе («Рогожину» — фамилия, не «рогожина»)."""
        if word not in self.cache:
            self.cache[word] = self.morph.parse(word)
        return [p for p in self.cache[word] if {"Name", "Surn", "Patr"} & set(p.tag.grammemes)]

    def first(self, word: str):
        if word not in self.cache:
            self.cache[word] = self.morph.parse(word)
        return self.cache[word][0]

    def statistics(self, paragraphs) -> None:
        for _, text in paragraphs:
            for m in WORD.finditer(text):
                w = m.group()
                k = w.lower().replace("ё", "е")
                if w[0].islower():
                    self.lower[k] += 1
                else:
                    self.capitalized[k] += 1
                    if not sentence_start(text, m.start()):
                        self.capital_inside[k] += 1
                        self.normal_inside[self.first(w).normal_form.replace("ё", "е")] += 1

    def is_name(self, word: str, at_start: bool) -> bool:
        k = word.lower().replace("ё", "е")
        if not word[0].isupper() or (len(word) > 1 and word.isupper()) or k in NOT_PEOPLE or k in TITLES:
            return False
        parses = self.cache.get(word) or self.morph.parse(word)
        self.cache[word] = parses
        # «Фу», «Ай», «Ну» угадыватель разбирает и как имя: служебное слово или междометие именем не считаем.
        if any(p.tag.POS in ("INTJ", "PRCL", "CONJ", "PREP", "NPRO") for p in parses):
            return False
        tagged = any({"Name", "Surn", "Patr"} & set(p.tag.grammemes) for p in parses)
        # «Москва», «Газпром» из словаря — не люди; но угаданное для незнакомого слова «название организации»
        # («Мозес» 42 раза с заглавной внутри предложения) — не повод отбросить имя.
        if not tagged and {"Geox", "Orgn"} & set(parses[0].tag.grammemes) and (parses[0].is_known or self.capital_inside[k] < 3):
            return False
        if {"ADJF", "Poss"} <= set(parses[0].tag.grammemes) or self.possessive(k):  # «Зинина», «Лидиной» — чьё-то
            return False
        if self.lower[k] > self.capital_inside[k]:  # нарицательное, просто в начале предложения
            return False
        if at_start:
            return self.capital_inside[k] > 0 or (tagged and self.lower[k] == 0) or (
                self.normal_inside[self.first(word).normal_form.replace("ё", "е")] >= 2 and self.lower[k] == 0)
        # «Медуза» — прозвище: в тексте есть и «медуза» строчными, но с заглавной внутри предложения чаще.
        return tagged or self.capital_inside[k] >= 2 or (self.capital_inside[k] >= 1 and self.lower[k] == 0)

    POSSESSIVE = re.compile(r"^(.{2,}?)[иы]н(?:а|о|ы|ой|ою|ому|ым|ом|ых|ыми|у|е)?$")

    def possessive(self, k: str) -> bool:
        """«Зинина», «Анину», «Ксенин»: притяжательное от имени, которое в книге встречается намного чаще.
        Настоящая фамилия («Рогожин») остаётся: «Рогожа» в книге нет."""
        m = self.POSSESSIVE.match(k)
        if not m:
            return False
        owner = max(self.capitalized[m.group(1) + "а"], self.capitalized[m.group(1) + "я"])
        return owner >= max(5, 3 * self.capitalized[k])

    def chain_key(self, words: list[str], expected_gender: str | None = None,
                  expected_case: str | None = None) -> tuple[str, list, bool, bool]:
        """Ключ цепочки «Евгения Павловича» → «евгений павлович»: падеж и род согласуются по всем словам.
        Возвращает (ключ, [(род, роль)], семья, именительный падеж)."""
        named = [self.named(w) for w in words]
        if expected_case:
            named = [[p for p in opt if p.tag.case == expected_case] or opt for opt in named]
        options = [[p for p in opt if "sing" in p.tag.grammemes or "Sgtm" in p.tag.grammemes] for opt in named]
        if expected_gender:
            options = [[p for p in opt if p.tag.gender == expected_gender] or opt for opt in options]
        # «Улямов», «Бахмутов» словарь разбирает только как множественное («Улям», «Бахмут»), но это фамилия
        # одного человека; «Бобриковы», «Бобриковых» — семья, даже если словарь фамилии не знает.
        # «Санек» словарь видит как множественное от «Санька», но семья — только фамилия во множественном:
        # имя или прозвище без единственного разбора — один человек, ключ — как написано.
        surname_nom = [bool(self.named(w)) and not opt and (bool(SINGLE_SURNAME.search(w.lower())) or
                       not any("Surn" in p.tag.grammemes for p in self.named(w)))
                       for w, opt in zip(words, options)]
        family = any((self.named(w) and not opt and not single) or (not self.named(w) and FAMILY_SURNAME.search(w.lower()))
                     for w, opt, single in zip(words, options, surname_nom))
        shared = None
        for opt in options:
            if opt:
                cases = {(p.tag.case, p.tag.gender) for p in opt}
                shared = cases if shared is None else shared & cases
        genders = {gender for _, gender in (shared or ()) if gender in ("masc", "femn")}
        agreed_gender = expected_gender or (next(iter(genders)) if len(genders) == 1 else None)
        keys, info = [], []
        nominative = True
        for w, opt, single in zip(words, options, surname_nom):
            low = w.lower().replace("ё", "е")
            if single:
                keys.append(low)
                is_surname = any("Surn" in p.tag.grammemes for p in self.named(w))
                info.append(("m" if is_surname else None, "Surn" if is_surname else "Name"))
                continue
            if not opt and not self.named(w) and FAMILY_SURNAME.search(low):
                keys.append(FAMILY_SURNAME.sub(lambda m: m.group(1), low))  # «бобриковых» → «бобриков»
                info.append((None, "Surn"))
                nominative = False
                continue
            if not opt:
                parses = self.named(w)
                if parses:  # только множественное: «Епанчиных» — семья
                    keys.append(parses[0].normal_form.replace("ё", "е"))
                    info.append((None, next(r for r in ("Name", "Patr", "Surn") if r in parses[0].tag.grammemes)))
                else:  # нет в словаре: «Рогожина», «Фердыщенка» → форма, которая сама встречается в книге
                    adjective = self.first(w)
                    gender_hint = agreed_gender
                    if not gender_hint and low.endswith("ой") and {p.tag.gender for p in self.morph.parse(w)} == {"femn"}:
                        gender_hint = "femn"
                    base = self.unknown_base(low, gender_hint)
                    if adjective.tag.POS == "ADJF" and adjective.normal_form.endswith(("ский", "цкий", "ской", "цкой")):
                        normal = adjective.inflect({"nomn", "sing", agreed_gender or "masc"})
                        base = (normal.word if normal else adjective.normal_form).replace("ё", "е")
                    keys.append(base)
                    info.append((None, None))
                    nominative = nominative and keys[-1] == low
                continue
            if shared:
                agreed = [p for p in opt if (p.tag.case, p.tag.gender) in shared]
                opt = agreed or opt
            forms: dict[str, list] = collections.defaultdict(list)
            for p in opt:
                inflected = p.inflect({"nomn", "sing"})
                forms[(inflected.word if inflected else p.normal_form).replace("ё", "е")].append(p)
            # Одна форма — разные слова («Лебедева»: его или она; «Александра»): чаще встречающаяся в книге.
            # Словарь может не знать уменьшительного («Кирюху» → «кирюх»): тогда форма, которая есть в книге.
            fallback = self.unknown_base(low, agreed_gender)
            # Сама форма из текста — ключ, только если она похожа на именительный уменьшительного имени
            # («Владя» по словарю — падеж несуществующего «Владь»), но не «Афанасием» или «Ивановича».
            as_written = fallback == low and self.capitalized[low] >= 3 and all("Name" in p.tag.grammemes for p in opt) \
                and (low.endswith(("а", "я")) or (all(self.capitalized[f] == 0 for f in forms) and not any(
                    self.capitalized[x.word.replace("ё", "е")] > 0 for p in opt for x in p.lexeme if x.word != low)))
            # «Кэле», «Мити»: несклоняемое иностранное имя — словарь видит падеж («Кэля», «Митя»), которого
            # в книге нет ни разу; ключ — как написано.
            if (fallback != low or as_written) and fallback not in forms and all(self.capitalized[f] == 0 for f in forms) \
                    and self.capitalized[fallback] > 0:
                forms[fallback] = forms[max(forms, key=lambda f: self.capitalized[f])]
            # Словарь видит в «Влади» несклоняемую фамилию, а в книге 148 раз «Владя» — это его падеж.
            if fallback != low and fallback not in forms and self.capitalized[fallback] >= 5 * max(
                    self.capitalized[f] for f in forms) and all("Fixd" in p.tag.grammemes or "Name" in p.tag.grammemes for p in opt):
                forms[fallback] = forms[max(forms, key=lambda f: self.capitalized[f])]
            key = max(forms, key=lambda f: (self.capitalized[f], f == low))
            chosen = forms[key][0]
            keys.append(key)
            # Несклоняемое (как написано, других форм в книге нет) — в любом падеже, в том числе подлежащее.
            indeclinable = key == low and as_written and not low.endswith(("а", "я"))
            nominative = nominative and (indeclinable or any(p.tag.case == "nomn" for p in forms[key]))
            gender = "f" if chosen.tag.gender == "femn" else "m" if chosen.tag.gender == "masc" else None
            info.append((gender, next(r for r in ("Name", "Patr", "Surn") if r in chosen.tag.grammemes)))
        return " ".join(keys), info, family, nominative

    def unknown_base(self, low: str, expected_gender: str | None = None) -> str:
        def allowed(base):
            parses = self.named(base)
            return not expected_gender or not parses or any(p.tag.gender is None or p.tag.gender == expected_gender
                                                            or "ms-f" in p.tag.grammemes for p in parses)

        # Unknown surnames still inherit the explicit gender of a title/patronymic.
        # Frequency alone must not turn «генеральше Епанчиной» into a male surname.
        if expected_gender == "femn" and low.endswith("ой"):
            for base in (low[:-2] + "ая", low[:-2] + "а"):
                if allowed(base) and self.capitalized[base] > 0:
                    return base
        # Adjectival surnames absent from the dictionary: «Тоцким» → «Тоцкий»,
        # only when that nominative form actually occurs in the same text.
        for ending in ("ого", "ому", "ыми", "ых", "им", "ым", "ом"):
            if low.endswith(ending):
                stem = low[:-len(ending)]
                for suffix in ("ий", "ый", "ой"):
                    base = stem + suffix
                    if len(stem) >= 3 and allowed(base) and self.capitalized[base] >= max(2, self.capitalized[low] // 4):
                        return base
        # Из всех основ — самая частая в книге: «Кирюху» → «Кирюха» (48), а не звательное «Кирюх» (2).
        bases = []
        for ending in CASE_ENDINGS:
            if low.endswith(ending) and len(low) - len(ending) >= 3:
                stem = low[: -len(ending)]
                for base in (stem, stem + "о", stem + "а", stem + "я", stem + "ь"):
                    if base != low and allowed(base) and self.capitalized[base] >= max(2, self.capitalized[low] // 4):
                        # «Кирюха» само может быть именительным: к «Кирюх» — только если та форма чаще.
                        if ending in ("а", "я") and base == stem and self.capitalized[base] <= self.capitalized[low]:
                            continue
                        bases.append(base)
        return max(bases, key=lambda base: self.capitalized[base]) if bases else low

    def name_can_continue(self, words: list[str], following: str) -> bool:
        """Adjacent names must agree; an addressee and a speaker are not one long name."""
        def options(word):
            return [p for p in self.named(word) if "sing" in p.tag.grammemes or "Sgtm" in p.tag.grammemes]
        previous = [options(word) for word in words]
        next_options = options(following)
        shared = None
        for opt in previous:
            if opt:
                values = {(p.tag.case, p.tag.gender) for p in opt}
                shared = values if shared is None else shared & values
        if shared and next_options and not shared & {(p.tag.case, p.tag.gender) for p in next_options}:
            return False
        def surname_like(word):  # «Рогожин» нет в словаре; «Лебедеву» — только фамилия
            parses = self.named(word)
            return not parses or all("Surn" in p.tag.grammemes for p in parses)
        if surname_like(words[-1]) and surname_like(following):
            return False  # «сказал Рогожин Лебедеву»: две фамилии подряд — два человека
        if any("Patr" in p.tag.grammemes for opt in previous for p in opt):
            roles = {role for p in next_options for role in ("Name", "Patr", "Surn") if role in p.tag.grammemes}
            if "Name" in roles and "Surn" not in roles:
                return False
        return True

    def title_agrees(self, title, word: str) -> bool:
        """«князь Мышкин» — одно лицо; «сказал князь Рогожину» — князь и адресат в другом падеже."""
        cases = {p.tag.case for p in self.named(word)}
        return not cases or title.tag.case in cases or not {"nomn", "gent", "datv", "accs", "ablt", "loct"} & cases

    def run(self, book: Book, per_section: bool) -> dict:
        self.statistics(book.paragraphs)
        candidates: dict[tuple, Candidate] = {}

        paragraph = -1

        def add(key, kind, section, text, start, end) -> Candidate:
            scope = section if per_section else 0
            cand = candidates.get((scope, key)) or candidates.setdefault((scope, key), Candidate(key, kind, scope))
            cand.count += 1
            cand.sections[section] += 1
            if len(cand.examples) < 6 and (cand.count <= 3 or cand.count in (10, 40, 100)):
                cand.examples.append(snippet(text, start, end))
            cand.spots.append((paragraph, start, end))
            return cand

        for section, text in book.paragraphs:
            paragraph += 1
            words = list(WORD.finditer(text))
            mentions: list[Mention] = []
            i = 0
            while i < len(words):
                m = words[i]
                low = m.group().lower()
                title = None
                parsed = self.first(low)
                if parsed.normal_form in TITLES:
                    single = [p for p in self.cache[low] if p.normal_form in TITLES and "plur" not in p.tag.grammemes]
                    if not single:
                        i += 1  # «господа», «князей Мышкиных» — не один человек
                        continue
                    parsed = single[0]  # «доктора» — скорее «у доктора», чем «доктора пришли»
                    title = parsed.normal_form
                    j = i + 1
                    if j < len(words) and text[m.end():words[j].start()] == " " and self.is_name(words[j].group(), False) \
                            and self.title_agrees(parsed, words[j].group()):
                        i = j  # титул перед именем: «генерал Иволгин»
                        m = words[i]
                    else:
                        inflected = parsed.inflect({"nomn", "sing"})
                        cand = add(inflected.word if inflected else title, "title", section, text, m.start(), m.end())
                        cand.forms[m.group()] += 1
                        if parsed.tag.gender in ("masc", "femn"):
                            cand.genders["Title:" + ("f" if parsed.tag.gender == "femn" else "m")] += 1
                        mentions.append(Mention(m.start(), m.end(), cand, parsed.tag.case == "nomn"))
                        i += 1
                        continue
                if not self.is_name(m.group(), sentence_start(text, m.start())):
                    i += 1
                    continue
                run = [m]
                while i + 1 < len(words) and text[run[-1].end():words[i + 1].start()] == " " and self.is_name(words[i + 1].group(), False):
                    if not self.name_can_continue([x.group() for x in run], words[i + 1].group()):
                        break
                    i += 1
                    run.append(words[i])
                expected_case = parsed.tag.case if title else None
                first_index = i - len(run) + 1
                if not title and first_index > 0 and text[words[first_index-1].end():run[0].start()] == " ":
                    expected_case = {"к":"datv", "ко":"datv", "от":"gent", "из":"gent", "без":"gent",
                                     "для":"gent", "у":"gent", "около":"gent", "возле":"gent", "до":"gent",
                                     "над":"ablt", "перед":"ablt", "между":"ablt"}.get(words[first_index-1].group().lower())
                key, info, family, nominative = self.chain_key([x.group() for x in run],
                    parsed.tag.gender if title else None, expected_case)
                cand = add(("семья " + key) if family else key, "family" if family else "name", section, text, run[0].start(), run[-1].end())
                cand.forms[text[run[0].start():run[-1].end()]] += 1
                for gender, role in info:
                    if role:
                        cand.roles[role] += 1
                        if gender:
                            cand.genders[f"{role}:{gender}"] += 1
                if title:
                    cand.titles[title] += 1
                    if parsed.tag.gender in ("masc", "femn"):
                        cand.genders["Title:" + ("f" if parsed.tag.gender == "femn" else "m")] += 1
                mentions.append(Mention(run[0].start(), run[-1].end(), cand, nominative))
                i += 1
            # Apposition also identifies titles: «Иван Петрович, отставной генерал».
            # Otherwise a title belonging to two people can look unique from prefixes alone.
            for mention in mentions:
                if mention.cand.kind != "title":
                    continue
                previous = [m for m in mentions if m.cand.kind == "name" and m.end < mention.start]
                if not previous:
                    continue
                name = previous[-1]
                gap = text[name.end:mention.start]
                if len(gap) <= 64 and re.fullmatch(r"\s*,\s*(?:[А-ЯЁа-яё-]+\s+){0,3}", gap):
                    qualifiers = [self.first(w.group()) for w in WORD.finditer(gap)]
                    # «Иван Петрович, отставной генерал» — да; «Рогожин, князь же…» — перечисление, не приложение.
                    if qualifiers and all(q.tag.POS in ("ADJF", "PRTF", "ADVB") for q in qualifiers) \
                            and any(q.tag.POS in ("ADJF", "PRTF") for q in qualifiers):
                        name.cand.titles[mention.cand.key] += 1
            def describe(key, start, end, gender, section=section, text=text):
                cand = add(key, "title", section, text, start, end)
                cand.descriptor = True
                cand.forms[text[start:end]] += 1
                cand.speaker += 1
                cand.speaker_sections[section] += 1
                cand.genders["verb:" + ("f" if gender == "femn" else "m")] += 1
            self.section = section
            self.attribute_speakers(text, mentions, describe)
            present = {id(x.cand): x.cand for x in mentions}
            for a in present.values():
                for b in present.values():
                    if a is not b:
                        a.together[b.key] += 1
        return candidates

    def attribute_speakers(self, text: str, mentions: list[Mention], describe=None) -> None:
        """«— Реплика, — сказал князь. — Ещё реплика»: ремарки — нечётные куски между тире.
        describe(слово, начало, конец) — говорящий назван не именем: «— спросил черномазый», «— промычал лакей»."""
        if not text.startswith(("—", "–")):
            return
        offset = 1
        for index, part in enumerate(DASH.split(text[1:])):
            start = text.find(part, offset)
            if start < 0:
                return
            end = start + len(part)
            offset = end
            if index % 2 == 0:
                continue
            # A dash inside a spoken sentence is not necessarily a narrator's remark.
            # Only a nearby subject and past-tense verb in the opening clause count.
            clause = re.split(r"[.!?…;:(]", part, maxsplit=1)[0][:180]
            inside = [m for m in mentions if start <= m.start < start + len(clause)
                      and m.end <= start + len(clause) and m.nominative and m.cand.kind != "family"]
            verbs = [(w, self.past_verb(w.group())) for w in WORD.finditer(clause)]
            verbs = [(w, p) for w, p in verbs if p is not None]
            if not inside:
                if describe and verbs:
                    self.describe_speaker(clause, start, verbs[0], describe)
                continue
            pairs = []
            for w, parsed in verbs:
                for mention in inside:
                    left, right = sorted(((mention.start-start, mention.end-start), (w.start(), w.end())))
                    gap = clause[left[1]:right[0]]
                    if len(gap) > 48 or re.search(r"[.:;!?…]", gap):
                        continue
                    if "," in gap and not self.parenthetical(gap, mention.start - start > w.start()):
                        continue  # «— удивился, наконец, Рогожин» — да; «— сказал он, и Рогожин…» — нет
                    if any(other != mention and left[1] <= other.start-start < right[0] for other in inside):
                        continue
                    pairs.append(("," in gap, len(gap), mention, parsed))
            if not pairs:
                pairs = self.sole_speaker(clause, start, inside, verbs)
            if not pairs:
                continue
            *_, mention, verb = min(pairs, key=lambda pair: pair[:2])
            speaker = mention.cand
            speaker.speaker += 1
            speaker.speaker_sections[getattr(self, "section", 0)] += 1
            if verb.tag.gender in ("masc", "femn"):
                speaker.genders["verb:" + ("f" if verb.tag.gender == "femn" else "m")] += 1

    def past_verb(self, word: str):
        """Глагол ремарки: прошедшее («сказал», «орал» — словарь первым видит «орала») или настоящее в 3-м лице
        единственного числа («говорит», «кричит» — современная и переводная проза в настоящем времени)."""
        if word not in self.cache:
            self.cache[word] = self.morph.parse(word)
        return next((p for p in self.cache[word][:3] if p.tag.POS == "VERB" and (
            "past" in p.tag.grammemes or {"pres", "3per", "sing", "indc"} <= set(p.tag.grammemes))), None)

    def sole_speaker(self, clause: str, start: int, inside: list, verbs: list) -> list:
        """В ремарке назван ровно один человек, род первого глагола с ним согласуется и между ними нет другого
        глагола: «— завороженно сказал ещё красный и мокрый от слёз … Павлуша», «— Антоша, продолжая обыскивать
        карманы, машинально сунул…». Иначе говорящий неизвестен."""
        people = {id(m.cand): m for m in inside}
        if len(people) != 1 or not verbs:
            return []
        mention = next(iter(people.values()))
        w, verb = verbs[0]
        left, right = sorted(((mention.start - start, mention.end - start), (w.start(), w.end())))
        if right[0] - left[1] > 150 or any(left[1] <= x.start() < right[0] for x, _ in verbs[1:]):
            return []
        genders = {p.tag.gender for p in self.named(clause[mention.start - start:mention.end - start].split()[-1])}
        if verb.tag.gender in ("masc", "femn") and genders and verb.tag.gender not in genders:
            return []
        return [(True, right[0] - left[1], mention, verb)]

    def parenthetical(self, gap: str, name_after_verb: bool) -> bool:
        """Между глаголом и говорящим — только вводное в запятых: наречие, частица, деепричастие
        («— бормотала, улыбаясь, баба Катя»); перед самим именем ещё может стоять одно существительное
        без запятой («баба Катя», «тётя Маша»)."""
        words = list(WORD.finditer(gap))
        if name_after_verb and words and "," not in gap[words[-1].end():]:
            last = self.first(words[-1].group())
            if last.tag.POS == "NOUN" and last.tag.case == "nomn":
                words = words[:-1]
        return all(self.first(x.group()).tag.POS in ("ADVB", "PRCL", "CONJ", "PRED", "GRND") for x in words)

    def describe_speaker(self, clause: str, start: int, verb, describe) -> None:
        """Сразу после глагола речи — существительное-лицо или субстантивное прилагательное в именительном
        («— спросил черномазый», «— промычал удивленный лакей»): такой говорящий тоже кандидат."""
        w, parsed = verb
        if parsed.tag.gender not in ("masc", "femn"):
            return
        rest = list(WORD.finditer(clause, w.end()))
        for n, x in enumerate(rest[:4]):
            if re.search(r"[,—–]", clause[(rest[n - 1].end() if n else w.end()):x.start()]):
                return
            p = self.first(x.group())
            low = x.group().lower()
            if x.group()[0].isupper() or low in TITLES:
                return  # имя или обращение уже учтены как упоминание
            if p.tag.POS == "NPRO" and p.tag.case != "nomn":
                continue  # «— отвечал ему собеседник»
            # Профессия мужского рода у женщины («— спросила орнитолог»): род берётся у глагола.
            if p.tag.POS == "NOUN" and p.tag.case == "nomn" and "anim" in p.tag.grammemes and "sing" in p.tag.grammemes \
                    and (p.tag.gender == parsed.tag.gender or p.tag.gender == "masc"):
                describe(p.normal_form.replace("ё", "е"), start + x.start(), start + x.end(), parsed.tag.gender)
                return
            if {"Apro", "Anum"} & set(p.tag.grammemes) or p.normal_form in NOT_DESCRIPTORS:
                return  # «— отвечал тот», «— сказал другой»: кто это — неизвестно
            if p.tag.POS in ("ADJF", "PRTF") and p.tag.case == "nomn" and p.tag.gender == parsed.tag.gender:
                if n + 1 == len(rest) or clause[x.end():rest[n + 1].start()].strip():
                    describe(low.replace("ё", "е"), start + x.start(), start + x.end(), parsed.tag.gender)
                    return
                continue
            if p.tag.POS not in ("ADVB", "PRCL"):
                return


def snippet(text: str, start: int, end: int, width: int = 70) -> str:
    left, right = max(0, start - width), min(len(text), end + width)
    return ("…" if left else "") + text[left:start] + "[[" + text[start:end] + "]]" + text[end:right] + ("…" if right < len(text) else "")


def needs_contexts(c: Candidate) -> bool:
    """Обращение («генерал») и голая фамилия могут означать разных людей: для них — отрывки по всей книге."""
    return c.kind == "title" or (c.kind == "name" and " " not in c.key and bool(c.roles.get("Surn")))


def label_contexts(book: Book, c: Candidate, per_chapter: bool = False) -> dict:
    """Отрывки для «кто это»: предыдущий абзац (того же раздела) и абзац с упоминанием, слово в [[…]].
    Равномерно по книге (LABEL_SAMPLES) или, для обращения в романе, до LABEL_PER_CHAPTER из каждой главы,
    где оно встречается: тогда решение принимается и для каждой главы отдельно."""
    if per_chapter:
        by_section = collections.defaultdict(list)
        for spot in c.spots:
            by_section[book.paragraphs[spot[0]][0]].append(spot)
        # Где обращение частое, отрывков больше: один неуверенный ответ не должен решать главу.
        chosen = [spot for spots in by_section.values()
                  for spot in spread(spots, LABEL_PER_BUSY_CHAPTER if len(spots) >= 10 else LABEL_PER_CHAPTER)]
    else:
        chosen = spread(c.spots, LABEL_SAMPLES)
    out, where = [], []
    for paragraph, start, end in chosen:
        section, text = book.paragraphs[paragraph]
        left = max(0, start - 320)
        before = ("…" if left else "") + text[left:start]
        if start < 200 and paragraph > 0 and book.paragraphs[paragraph - 1][0] == section:
            previous = book.paragraphs[paragraph - 1][1]
            before = ("…" if len(previous) > 220 else "") + previous[-220:] + "\n" + before
        right = min(len(text), end + 160)
        out.append(before + "[[" + text[start:end] + "]]" + text[end:right] + ("…" if right < len(text) else ""))
        where.append(book.sections[section]["id"])
    return {"contexts": out, "context_sections": where}


def spread(items: list, limit: int) -> list:
    if len(items) <= limit:
        return list(items)
    step = len(items) / limit
    return [items[int(i * step + step / 2)] for i in range(limit)]


def gender_source(c: Candidate) -> tuple[str, str | None]:
    """Род и откуда он: глагол в ремарке («сказала») надёжнее всего, затем отчество, обращение, имя, фамилия
    («Ганя», «Ганечка» по словарю женского рода, но «— сказал Ганя»)."""
    for source in ("verb", "Patr", "Title", "Name", "Surn"):
        m, f = c.genders.get(source + ":m", 0), c.genders.get(source + ":f", 0)
        if m + f < (2 if source == "verb" else 1):
            continue
        if m >= 2 * f:
            return "m", source
        if f >= 2 * m:
            return "f", source
    return "?", None


def gender_of(c: Candidate) -> str:
    return gender_source(c)[0]


def display(c: Candidate) -> str:
    return c.forms.most_common(1)[0][0] if c.forms else c.key


def detect_collection(book: Book, candidates: dict) -> bool:
    """Сборник: у каждого рассказа свои герои. Мера — какая доля упоминаний десяти главных персонажей приходится
    на их «домашний» раздел: в сборнике почти все (0,96 на «Ночном взгляде»), в романе главные проходят через
    многие главы (0,2–0,55 на пяти проверенных романах, включая 57 коротких глав «Последнего дома»).
    Разделы с одинаковыми названиями («Тед», «Оливия», «Тед»…) — главы романа от разных рассказчиков."""
    if len(book.sections) < 3:
        return False
    titles = collections.Counter(s["title"] for s in book.sections)
    if sum(n for n in titles.values() if n > 1) > len(book.sections) / 3:
        return False
    named = sorted((c for c in candidates.values() if c.kind == "name"), key=lambda c: -c.count)[:10]
    total = sum(c.count for c in named)
    if not total:
        return False
    return sum(max(c.sections.values()) for c in named) / total >= COLLECTION_CONCENTRATION


# ---------------------------------------------------------------- запрос к LLM

PROMPT = """Ты помогаешь подготовить {what} к озвучке разными голосами.
Ниже — кандидаты, найденные в тексте автоматически: имена, фамилии, отчества, уменьшительные формы
и обращения («князь», «генеральша»). У каждого кандидата: номер, число упоминаний, сколько раз он
назван в ремарке диалога («— сказал князь»), род по тексту, обращения перед ним, с кем встречается
в одних абзацах, примеры.

Задача: собрать персонажей. Один персонаж часто записан по-разному: фамилия, имя и отчество,
уменьшительное имя, прозвище, обращение, разные падежи. Для каждого персонажа придумай постоянный
идентификатор латиницей (например "myshkin", "nastasya_filippovna") и перечисли ВСЕ номера кандидатов,
которые означают его.

Сопоставление должно быть железным: объединяй кандидатов, только если по тексту несомненно, что это
одно лицо (имя с отчеством стоят вместе, «Лиза» прямо названа «Елизаветой Петровной», примеры говорят
об одном человеке, одна и та же форма в разных падежах). Любое сомнение — кандидат в "other".
Лучше оставить настоящего персонажа в "other", чем склеить двух разных людей.

Правила:
- Персонажей в characters расположи по значимости в повествовании: сначала главные, затем
  второстепенные и эпизодические. Порядок нужен для приоритета личного голоса, не для объединения имён.
  Учитывай контекст частых обращений, но не закрепляй неоднозначное обращение за одним человеком.
- Если примеры однозначно описывают отдельного человека, сохрани его персонажем даже при редких
  упоминаниях или участии во вложенном рассказе. Невозможность склеить его с другими именами не
  означает, что надо удалить саму личность: можно оставить одного надёжного кандидата.
- Каждый номер кандидата укажи ровно один раз: либо у одного персонажа, либо в "other".
- Первым в "candidates" ставь самого надёжного кандидата персонажа (самое частое имя).
- name — полное имя из найденных форм (например «Лев Николаевич Мышкин», если такие формы есть);
  ничего не додумывай: ни полных имён, которых нет в тексте, ни пояснений в скобках.
- В "other" — не персонажи (места, книги, исторические лица, которых только упоминают), семьи
  во множественном числе, и всё, что нельзя уверенно отнести к одному лицу.
- Обращение без имени («князь», «генерал») отнеси к персонажу только при отсутствии других
  носителей этого обращения в доступных примерах и связях; если так называют нескольких — в "other".
- Отец и сын, муж и жена с одной фамилией — разные персонажи; общую фамилию без имени, если по
  примерам не ясно, кто это, отправь в "other".
- gender: "m", "f" или "?" — по тексту.
- Не придумывай номера, которых нет в списке. Ответ — только JSON без пояснений:
{{"characters": [{{"id": "...", "name": "полное имя", "gender": "m", "candidates": ["{p}1", "{p}7"]}}], "other": ["{p}9"]}}

Кандидаты:
{lines}
"""

VERIFY = """Проверка сопоставления персонажей в {what}.
Для каждого персонажа ниже: главный кандидат и остальные, которых к нему отнесли, с примерами из текста.
Для каждого остального кандидата ответь, тот ли это человек, что и главный:
"same" — несомненно тот же человек; "different" — другой человек или не человек; "unsure" — нельзя
уверенно сказать по примерам. Сомнение — это "unsure". Ответ — только JSON без пояснений:
{{"checks": [{{"character": "id персонажа", "anchor": "{p}1", "candidate": "{p}5", "verdict": "same"}}]}}
В каждом checks повтори id персонажа и номер главного кандидата из группы. Проверяй по примерам, не по памяти о книге.

{groups}
"""

LABELS = """Кто назван словом в [[…]] в каждом отрывке из {what}?
Персонажи (id: имя; как ещё называется):
{people}

Для каждого отрывка ответь id персонажа из списка; "other" — другой человек (нет в списке) или не человек;
"unsure" — по отрывку нельзя понять. Решай по самому отрывку: кто говорит, к кому обращаются, кто действует
рядом. Ответ — только JSON без пояснений, по одному ответу на каждый номер:
{{"answers": [{{"n": 1, "who": "id"}}]}}

Отрывки (слово «{label}»):
{snippets}
"""

SCHEMA = {
    "type": "object",
    "properties": {
        "characters": {"type": "array", "items": {"type": "object", "properties": {
            "id": {"type": "string"}, "name": {"type": "string"}, "gender": {"type": "string", "enum": ["m", "f", "?"]},
            "candidates": {"type": "array", "items": {"type": "string"}}},
            "required": ["id", "name", "gender", "candidates"]}},
        "other": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["characters", "other"],
}


def candidate_line(cid: str, c: Candidate, ids: dict) -> str:
    bits = [f"{cid} | {display(c)}", f"упоминаний {c.count}"]
    if c.speaker:
        bits.append(f"в ремарках {c.speaker}")
    bits.append(f"род {gender_of(c)}")
    if c.kind == "family":
        bits.append("мн. число (семья?)")
    if c.kind == "title":
        bits.append("обращение без имени")
    roles = [r for r, _ in c.roles.most_common()]
    if roles:
        bits.append("+".join({"Name": "имя", "Patr": "отчество", "Surn": "фамилия"}[r] for r in roles))
    if c.titles:
        bits.append("обращения при имени: " + ", ".join(t for t, _ in c.titles.most_common(3)))
    near = [ids[(c.scope, k)] for k, _ in c.together.most_common(8) if (c.scope, k) in ids][:4]
    if near:
        bits.append("рядом: " + ", ".join(near))
    line = " | ".join(bits)
    # Три примера — частым кандидатам и обращениям (их чаще путают), редким — один: запрос не раздувается.
    for e in c.examples[:3 if c.count >= 30 or c.kind == "title" else 2 if c.count >= 5 else 1]:
        line += f"\n    пример: {e}"
    return line


def extract(args) -> None:
    started = time.time()
    book = read_book(args.book)
    extractor = Extractor()
    text = normalized(book)  # разбор — по очищенному тексту, отпечатки — по исходному
    found = extractor.run(text, per_section=False)
    collection = args.scope == "section" or (args.scope == "auto" and detect_collection(book, found))
    if collection:
        book = stories(args.book, book)
        text = normalized(book)
        found = Extractor().run(text, per_section=True)
    else:
        # Роман — по главам: обращение («генерал») решается для каждой главы, где оно однозначно.
        chaptered = chapters(args.book)
        if len(chaptered.sections) > len(book.sections):
            book, text = chaptered, normalized(chaptered)
            found = Extractor().run(text, per_section=False)
    groups: dict[int, list] = collections.defaultdict(list)
    for c in found.values():
        if c.descriptor and c.speaker < 2 and not c.titles:
            continue  # описание говорящего, встреченное один раз, — не персонаж
        if c.count >= args.min_count or c.speaker > 0 or (c.kind == "name" and (c.roles.get("Name") or c.roles.get("Patr"))):
            groups[c.scope].append(c)
    os.makedirs(args.out, exist_ok=True)
    ids, records, requests = {}, [], []
    for scope in sorted(groups):
        ranked = sorted(groups[scope], key=lambda c: (-(c.count + 3 * c.speaker), c.key))[: args.max_candidates]
        prefix = f"s{scope + 1}c" if collection else "c"
        for n, c in enumerate(ranked, 1):
            ids[(scope, c.key)] = f"{prefix}{n}"
        for c in ranked:
            records.append({
                "id": ids[(scope, c.key)], "scope": book.sections[scope]["id"] if collection else "book", "key": c.key,
                "display": display(c), "kind": c.kind, "count": c.count, "speaker": c.speaker, "gender": gender_of(c),
                "gender_source": gender_source(c)[1],
                "roles": dict(c.roles), "titles": dict(c.titles), "forms": dict(c.forms.most_common()),
                "sections": [book.sections[s]["id"] for s in sorted(c.sections)],
                # По разделам: в романе по главам обращение может принадлежать разным людям — их реплики и
                # упоминания считаются только там, где обращение за ними.
                "section_counts": {book.sections[s]["id"]: n for s, n in sorted(c.sections.items())},
                "speaker_sections": {book.sections[s]["id"]: n for s, n in sorted(c.speaker_sections.items())},
                "together": [ids[(scope, k)] for k, _ in c.together.most_common(8) if (scope, k) in ids],
                # Сколько абзацев с каждым кандидатом: голос делят только те, кто почти не встречается.
                "together_counts": {ids[(scope, k)]: n for k, n in c.together.most_common() if (scope, k) in ids},
                "examples": c.examples,
                **(label_contexts(text, c, per_chapter=not collection and c.kind == "title") if needs_contexts(c) else {}),
            })
        if not ranked:
            continue
        what = f"рассказ «{book.sections[scope]['title']}» из книги «{book.title}»" if collection else f"книгу «{book.title}»"
        prompt = PROMPT.format(what=what, p=prefix, lines="\n".join(candidate_line(ids[(scope, c.key)], c, ids) for c in ranked))
        name = book.sections[scope]["id"] if collection else "book"
        requests.append({"name": name, "title": book.sections[scope]["title"] if collection else book.title,
                         "sections": [name] if collection else [s["id"] for s in book.sections],
                         "candidates": [ids[(scope, c.key)] for c in ranked], "prompt": prompt})
        with open(os.path.join(args.out, f"llm_prompt_{name}.txt"), "w", encoding="utf-8") as f:
            f.write(prompt)
    # Отпечатки предложений → раздел. Повтор в разных разделах раздела не указывает — такие убираются.
    index: dict[str, str | None] = {}
    for section, text in book.paragraphs:
        sid = book.sections[section]["id"]
        for fp in sentence_fingerprints(text):
            index[fp] = sid if index.get(fp, sid) == sid else None
    with open(args.book, "rb") as f:
        file_sha = hashlib.sha256(f.read()).hexdigest()
    content_sha = hashlib.sha256("\n".join(letters(t) for _, t in book.paragraphs).encode()).hexdigest()
    identity = {"title": book.title, "author": book.author, "file_sha256": file_sha, "content_sha256": content_sha}
    save(args.out, "candidates.json", {"book": book.title, "scope": "section" if collection else "book",
                                       "sections": book.sections, "candidates": records})
    save(args.out, "llm_request.json", {"book": book.title, "scope": "section" if collection else "book",
                                        "response_schema": SCHEMA, "requests": requests})
    save(args.out, "book_index.json", {"book": identity, "sections": book.sections,
                                       "fingerprint": {"algorithm": "fnv1a64-48", "min_letters": 24},
                                       "sentences": {k: v for k, v in index.items() if v}})
    sizes = [len(r["prompt"]) for r in requests]
    print(f"«{book.title}»: разделов {len(book.sections)}, абзацев {len(book.paragraphs)}, "
          f"{'сборник — персонажи по разделам' if collection else 'роман — персонажи на всю книгу'}, "
          f"кандидатов {len(records)}, запросов {len(requests)} (до {max(sizes, default=0)} знаков), {time.time() - started:.1f} с")
    by_id = {r["id"]: r for r in records}
    for r in requests[: args.show_requests]:
        print(f"  [{r['name']}] {r['title']}: " + ", ".join(
            f"{by_id[c]['display']}({by_id[c]['count']},{by_id[c]['gender']})" for c in r["candidates"][: args.show]))


def read_json(path: str):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def save(folder: str, name: str, data, compact: bool = False) -> None:
    import tempfile
    target = os.path.join(folder, name)
    fd, temp = tempfile.mkstemp(prefix=".mytts-", suffix=".tmp", dir=folder)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, **({"separators": (",", ":")} if compact else {"indent": 1}))
        os.replace(temp, target)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


# ---------------------------------------------------------------- LLM: Ollama Cloud или API DeepSeek

HERE = os.path.dirname(os.path.abspath(__file__))
PROVIDERS = {
    "ollama": {"endpoint": "https://ollama.com", "model": "deepseek-v4.1-flash", "key": "OLLAMA_API_KEY"},
    "deepseek": {"endpoint": "https://api.deepseek.com", "model": "deepseek-flash", "key": "DEEPSEEK_API_KEY"},
}
# Предел ответа по умолчанию: с размышлением / без его.
MAX_TOKENS = {
    "ollama": {"think": 80000, "plain": 16000},
    "deepseek": {"think": 64000, "plain": 16000},
}


class LLMError(RuntimeError):
    """Ошибка сервиса LLM: код HTTP, текст ответа и (для 429) сколько секунд ждать до повтора."""

    def __init__(self, status: int, message: str, retry_after: str | None = None, max_output_tokens: int | None = None) -> None:
        super().__init__(f"HTTP {status}: {message}")
        self.status = status
        self.retry_after = retry_after
        self.max_output_tokens = max_output_tokens

    def wait_seconds(self, default: float) -> float:
        """Сколько ждать до повтора: заголовок Retry-After сервиса (не больше двух минут), иначе заданное."""
        if not self.retry_after:
            return default
        try:
            return min(120.0, max(1.0, float(self.retry_after)))
        except ValueError:
            return default


def load_env() -> None:
    """Ключи из файла .env рядом со скриптом или в текущей папке (строки KEY=VALUE); переменные окружения главнее."""
    for folder in (HERE, os.getcwd()):
        path = os.path.join(folder, ".env")
        if not os.path.isfile(path):
            continue
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                name, value = line.split("=", 1)
                os.environ.setdefault(name.strip(), value.strip().strip("\"'"))


_THINKING_CONTROLS: dict[tuple, list] = {}
_MODEL_LIMITS: dict[tuple, int] = {}
_EFFORT_HINT: dict[tuple, str] = {}


def thinking_control(values: list, enabled: bool):
    if values == [False]:
        return False
    if values == [True]:
        return True
    if any(v is enabled for v in values):
        return enabled
    levels = [v for v in values if isinstance(v, str)]
    # «medium», как обычное «think: true» в первых версиях: на «high» модель на большом списке кандидатов
    # может размышлять до предела ответа и не ответить вовсе.
    order = ("medium", "high", "low", "max", "minimal") if enabled else ("minimal", "low", "medium", "high", "max")
    if levels:
        return next((v for v in order if v in levels), levels[0])
    return enabled


EFFORTS = ("max", "high", "medium", "low", "minimal")
THINK_TEMPERATURE = 0.6


def ollama_thinking(endpoint: str, model: str, key: str, enabled: bool, effort: str | None = None):
    cache_key = (endpoint.rstrip("/"), model)
    if cache_key not in _THINKING_CONTROLS:
        req = urllib.request.Request(endpoint.rstrip("/") + "/api/show", data=json.dumps({"model":model}).encode(),
            headers={"Content-Type":"application/json", "Authorization":"Bearer " + key})
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                data = json.load(response)
            thinking = data.get("thinking")
            values = thinking.get("values") if isinstance(thinking, dict) else None
            _THINKING_CONTROLS[cache_key] = values if isinstance(values, list) else []
        except urllib.error.HTTPError as e:
            status = e.code
            e.close()
            if status not in (404, 405):
                raise LLMError(status, "сервер отклонил сведения о модели") from None
            _THINKING_CONTROLS[cache_key] = []
    values = _THINKING_CONTROLS[cache_key]
    if enabled and values and thinking_control(values, True) is False:
        raise ValueError("Выбранная модель не поддерживает размышление")
    if enabled and effort and effort in EFFORTS:
        # Ступенью ниже, если на прежнем уровне размышление не уложилось в предел ответа: ближайший из
        # поддерживаемых моделью уровней не выше запрошенного.
        lower = [v for v in EFFORTS[EFFORTS.index(effort):] if v in values]
        supported = [v for v in EFFORTS if v in values]
        if lower or supported:
            return (lower or supported[-1:])[0]
    return thinking_control(values, enabled)


def temperature(think: bool) -> float:
    """Размышляющие модели DeepSeek при температуре 0 склонны зацикливаться: размышление повторяется, пока не
    кончится предел ответа, и ответа нет. Разработчики рекомендуют 0,5–0,7; без размышления — 0 (стабильнее).
    Ненулевая температура к тому же делает независимыми ответы для голосования."""
    return THINK_TEMPERATURE if think else 0.0


def request_chat(provider: str, endpoint: str, model: str, key: str, prompt: str, think: bool, max_tokens: int, timeout: int,
                 effort: str | None = None) -> dict:
    """Один запрос. Возвращает content, thinking (длина), причину остановки и токены в общем виде."""
    if provider == "deepseek":
        # Оригинальный API DeepSeek (api.deepseek.com): размышление — thinking, ответ — в reasoning_content.
        url = endpoint.rstrip("/") + "/chat/completions"
        body = {"model": model, "stream": False, "max_tokens": max_tokens, "temperature": temperature(think),
                "thinking": {"type": "enabled" if think else "disabled"},
                "messages": [{"role": "user", "content": prompt}]}
        if think:
            body["reasoning_effort"] = effort if effort in ("high", "medium", "low") else ("low" if effort else "high")
    else:
        url = endpoint.rstrip("/") + "/api/chat"
        body = {"model": model, "stream": False, "think": ollama_thinking(endpoint, model, key, think, effort), "options": {"temperature": temperature(think), "num_predict": max_tokens},
                "messages": [{"role": "user", "content": prompt}]}
    req = urllib.request.Request(url, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            data = json.loads(response.read())
    except urllib.error.HTTPError as e:  # текст ошибки сервиса, без ключа
        retry = e.headers.get("retry-after") if e.headers else None
        # Keep only the numeric limit; never retain/log arbitrary response bodies.
        try:
            message = json.loads(e.read()).get("error", "")
            if isinstance(message, dict):  # DeepSeek: {"error": {"message": "...", "type": ...}}
                message = message.get("message", "")
            # Ollama: «maximum output tokens (65536)»; DeepSeek: «valid range of max_tokens is [1, 65536]».
            match = re.search(r"maximum output tokens \((\d+)\)|max_tokens[^\[]*\[\s*1\s*,\s*(\d+)\s*\]", message) \
                if isinstance(message, str) else None
            cap = int(match.group(1) or match.group(2)) if match else None
        except (ValueError, TypeError):
            cap = None
        finally:
            e.close()
        raise LLMError(e.code, "сервер отклонил запрос", retry, cap) from None
    if provider == "deepseek":
        choice = data["choices"][0]
        message = choice.get("message", {})
        usage = data.get("usage", {})
        thinking = message.get("reasoning_content") or message.get("reasoning") or ""
        return {"content": message.get("content") or "", "thinking_chars": len(thinking),
                "done_reason": choice.get("finish_reason"), "prompt_tokens": usage.get("prompt_tokens"),
                "output_tokens": usage.get("completion_tokens")}
    message = data.get("message", {})
    return {"content": message.get("content", ""), "thinking_chars": len(message.get("thinking") or ""),
            "thinking_control": body["think"], "done_reason": data.get("done_reason"), "prompt_tokens": data.get("prompt_eval_count"),
            "output_tokens": data.get("eval_count")}


def llm(args) -> None:
    load_env()
    preset = PROVIDERS[args.provider]
    args.model = args.model or preset["model"]
    args.endpoint = args.endpoint or preset["endpoint"]
    key = os.environ.get(preset["key"], "")
    if not key:
        sys.exit(f"Нет ключа: задайте {preset['key']} в переменной окружения или в файле .env (см. README.md)")
    request = read_json(os.path.join(args.dir, "llm_request.json"))
    by_id = {c["id"]: c for c in read_json(os.path.join(args.dir, "candidates.json"))["candidates"]}
    folder = os.path.join(args.dir, "answers")
    os.makedirs(folder, exist_ok=True)

    input_sha = artifact_identity(args.dir)

    def identity(prompt: str, think: bool | None = None) -> str:
        think = args.think if think is None else think
        return cache_fingerprint(args.provider, args.endpoint, args.model, think, prompt,
                                 args.max_tokens or MAX_TOKENS[args.provider]["think" if think else "plain"], input_sha)

    def cached(prompt: str, name: str, verify: bool = False, check=None, think: bool | None = None) -> bool:
        if args.redo:
            return False
        answer = load_answer(os.path.join(folder, name + ".json"))
        meta = load_answer(os.path.join(folder, name + ".meta.json"))
        good = check(answer) if check else valid_response(answer, verify)
        return bool(good and meta and meta.get("request_sha256") == identity(prompt, think)
                    and meta.get("done_reason") != "length")

    def chat(prompt: str, name: str, verify: bool = False, check=None, think: bool | None = None) -> str:
        think = args.think if think is None else think
        requested_limit = args.max_tokens or MAX_TOKENS[args.provider]["think" if think else "plain"]
        model_key = (args.provider, args.endpoint, args.model)
        limit = min(requested_limit, _MODEL_LIMITS.get(model_key, requested_limit))
        delay = 2.0
        effort = _EFFORT_HINT.get(model_key)  # прошлый ответ уже не уложился — сразу короче
        for attempt in range(1, 4):
            started = time.time()
            try:
                reply = request_chat(args.provider, args.endpoint, args.model, key, prompt, think, limit, args.timeout,
                                     **({"effort": effort} if effort else {}))
            except LLMError as e:
                if attempt < 3 and e.status == 400 and e.max_output_tokens and 0 < e.max_output_tokens < limit:
                    limit = e.max_output_tokens
                    _MODEL_LIMITS[model_key] = limit
                    print(f"  [{name}] API ограничивает ответ {limit} токенами; повторяем с допустимым пределом", flush=True)
                    continue
                if attempt >= 3 or e.status not in (429, 500, 502, 503, 504):
                    raise
                delay = e.wait_seconds(delay * 2)
                print(f"  [{name}] HTTP {e.status}; ждём {delay:.0f} с и повторяем ({attempt + 1}/3)", flush=True)
                time.sleep(delay)
                continue
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                if attempt >= 3:
                    raise
                print(f"  [{name}] временный сетевой сбой {type(e).__name__}; ждём {delay:.0f} с и повторяем ({attempt + 1}/3)", flush=True)
                time.sleep(delay)
                delay = min(120, delay * 2)
                continue
            content = reply.pop("content")
            answer = normalize_answer(parse_answer(content))
            if reply.get("done_reason") == "length" or not (check(answer) if check else valid_response(answer, verify)):
                # Причина и сам ответ — для разбора: оборван по лимиту, нет JSON или JSON не той формы.
                if reply.get("done_reason") == "length":
                    why = f"оборван по лимиту {limit} токенов"
                elif answer is None:
                    why = "в ответе нет целого JSON"
                else:
                    why = "JSON не той формы или неполный"
                why += (f" (ответ {len(content)} знаков, размышление {reply.get('thinking_chars')} знаков, "
                        f"токенов {reply.get('prompt_tokens')}→{reply.get('output_tokens')})")
                with open(os.path.join(folder, f"{name}.failed{attempt}.txt"), "w", encoding="utf-8") as f:
                    f.write(content)
                if attempt < 3:
                    if reply.get("done_reason") == "length" and think:
                        # Размышление не уложилось в предел ответа: следующая попытка — ступенью короче, но с размышлением.
                        current = effort or reply.get("thinking_control") or "high"
                        effort = EFFORTS[min(len(EFFORTS) - 1, EFFORTS.index(current) + 1)] if current in EFFORTS else "medium"
                        _EFFORT_HINT[model_key] = effort
                        why += f"; размышление: {effort}"
                    print(f"  [{name}] {why}; повторяем ({attempt + 1}/3)", flush=True)
                    continue
                raise ValueError(f"LLM не вернула полный JSON нужного формата: {why}; ответ не сохранён")
            meta = dict(reply, provider=args.provider, model=args.model, content_chars=len(content), effort=effort,
                        request_sha256=identity(prompt, think), thinking=think, endpoint=args.endpoint, max_tokens=requested_limit, effective_max_tokens=limit, input_sha256=input_sha,
                        seconds=round(time.time() - started, 1), attempt=attempt)
            # Never expose a partly written reply as a completed cache entry.
            save(folder, name + ".json", answer)
            save(folder, name + ".meta.json", meta)
            return (f"{meta['seconds']} с, ответ {meta['content_chars']} знаков, размышление {meta['thinking_chars']}, "
                    f"токенов {meta['prompt_tokens']}→{meta['output_tokens']}, {meta['done_reason']}")
        raise RuntimeError("Не удалось получить ответ LLM")

    votes = max(1, getattr(args, "votes", 1) or 1)

    def ask(r: dict) -> str:
        name = r["name"]
        if votes == 1:
            line = "основной ответ из кэша" if cached(r["prompt"], name) else chat(r["prompt"], name)
        else:
            # Несколько независимых ответов: склейка остаётся, только если её дало большинство.
            parts, done = [], []
            for n in range(1, votes + 1):
                run = f"{name}.run{n}"
                try:
                    parts.append(f"{n}: " + ("из кэша" if cached(r["prompt"], run) else chat(r["prompt"], run).split(",")[0]))
                    done.append(n)
                except (ValueError, RuntimeError) as e:  # один ответ не удался — остальные ещё могут дать большинство
                    parts.append(f"{n}: не получен ({str(e)[:80]})")
            if len(done) < votes // 2 + 1:
                raise ValueError(f"получено {len(done)} ответов из {votes}, нужно большинство; повторите запуск")
            runs = [load_answer(os.path.join(folder, f"{name}.run{n}.json")) for n in done]
            combined = combine_answers(runs, r, by_id)
            meta = load_answer(os.path.join(folder, f"{name}.run{done[0]}.meta.json"))
            save(folder, name + ".json", combined)
            save(folder, name + ".meta.json", dict(meta, votes=len(done), done_reason="stop"))
            line = f"ответов {len(done)} из {votes} ({'; '.join(parts)})"
        answer = load_answer(os.path.join(folder, name + ".json"))
        line += check_labels(r, answer, name)
        prompt = verify_prompt(r, answer, by_id, request["scope"] == "section") if answer else None
        if not prompt:
            return line + "; проверять нечего"
        complete = lambda checked: verification_complete(checked, answer, r, by_id)
        if cached(prompt, name + ".verify", True, check=complete):
            return line + "; проверка: из кэша"
        try:  # неполный ответ (пропущены или повторены проверки) — повтор, до трёх попыток
            return line + "; проверка: " + chat(prompt, name + ".verify", True, check=complete)
        except ValueError:
            # И после повторов неполно: берём ответ как есть. Непроверенные склейки не принимаются (apply),
            # остальная книга обрабатывается — один неполный ответ не останавливает всё.
            verification = chat(prompt, name + ".verify", True)
            return line + "; проверка неполна (непроверенные склейки — в «прочих»): " + verification

    def check_labels(r: dict, answer: dict | None, name: str) -> str:
        """Обращения и голые фамилии: кто это в каждом из отрывков по всей книге."""
        if not answer:
            return ""
        done = 0
        for _, ref in label_targets(r, answer, by_id):
            for suffix, prompt, lo, hi in label_requests(r, answer, by_id, ref, request["scope"] == "section"):
                check = lambda a, count=hi - lo: label_votes(a, count) is not None
                # «Кто это в отрывке» — выбор из списка: без размышления быстрее и не зацикливается.
                if not cached(prompt, name + suffix, check=check, think=LABEL_THINK):
                    chat(prompt, name + suffix, check=check, think=LABEL_THINK)
            done += 1
        return f"; обращений и фамилий проверено по отрывкам: {done}" if done else ""

    started = time.time()
    todo = request["requests"]
    print(f"Разделов {len(todo)}, {args.provider}: {args.model}, "
          f"размышление {'вкл' if args.think else 'выкл'}, одновременно {args.parallel}; готовый кэш переиспользуется", flush=True)
    failures = []
    with concurrent.futures.ThreadPoolExecutor(args.parallel) as pool:
        for r, line in zip(todo, pool.map(lambda r: safe(ask, r), todo)):
            print(f"  [{r['name']}] {r['title']}: {line}", flush=True)
            if line.startswith("ошибка:"):
                failures.append(r["name"])
    if failures:
        raise RuntimeError("Не завершены запросы LLM: " + ", ".join(failures) + ". Повторите запуск; готовые ответы сохранены.")
    print(f"Готово за {time.time() - started:.1f} с")


def safe(fn, r) -> str:
    try:
        return fn(r)
    except Exception as e:  # сетевой сбой одного рассказа не останавливает остальные
        return f"ошибка: {type(e).__name__}: {str(e)[:160]}"


def character_refs(raw: dict, r: dict, candidates: dict) -> list[str]:
    refs = raw.get("candidates", [])
    if not isinstance(refs, list):
        return []
    own = set(r["candidates"])
    refs = list(dict.fromkeys(ref for ref in refs if isinstance(ref, str) and ref in own and ref in candidates))
    # Names anchor a merge, never a generic title/family. Most complete names first.
    return sorted(refs, key=lambda ref: (candidates[ref]["kind"] != "name",
        -len(candidates[ref]["key"].split()), -candidates[ref]["count"], ref))


def verification_complete(checked: dict | None, answer: dict, r: dict, candidates: dict) -> bool:
    if not valid_response(checked, True):
        return False
    expected = []
    for raw in answer["characters"]:
        refs = character_refs(raw, r, candidates)
        expected.extend((raw["id"], refs[0], ref) for ref in refs[1:])
    actual = [(check["character"], check["anchor"], check["candidate"]) for check in checked["checks"]]
    return collections.Counter(actual) == collections.Counter(expected) and len(actual) == len(set(actual))


def verification_verdicts(checked: dict | None, answer: dict | None, r: dict, candidates: dict) -> dict:
    verdicts = {}
    expected = {}
    raw_characters = (answer or {}).get("characters", [])
    for raw in raw_characters if isinstance(raw_characters, list) else []:
        if not isinstance(raw, dict):
            continue
        refs = character_refs(raw, r, candidates)
        for ref in refs[1:]:
            expected.setdefault(ref, []).append((str(raw.get("id", "")), refs[0]))
    checks = (checked or {}).get("checks", [])
    if not isinstance(checks, list):
        return verdicts
    seen = collections.Counter()
    for check in checks:
        if not isinstance(check, dict) or not isinstance(check.get("candidate"), str):
            continue
        ref = check["candidate"]
        # Legacy unbound verdicts are intentionally not used for a new merge.
        pair = (check.get("character"), check.get("anchor"))
        if not all(isinstance(x, str) for x in pair):
            continue
        if pair not in expected.get(ref, []):
            continue
        key = (pair[0], pair[1], ref)
        seen[key] += 1
        verdicts[key] = check.get("verdict") if seen[key] == 1 else "invalid"  # повтор — испорченный ответ
    return verdicts


def verify_prompt(r: dict, answer: dict, by_id: dict, collection: bool) -> str | None:
    """Второй запрос: каждую склейку подтверждает отдельный ответ «тот же / другой / не уверена»."""
    groups = []
    raw_characters = answer.get("characters", [])
    for raw in raw_characters if isinstance(raw_characters, list) else []:
        if not isinstance(raw, dict):
            continue
        refs = character_refs(raw, r, by_id)
        if len(refs) < 2:
            continue
        lines = [f"Группа id={raw.get('id', '')}. Главный: {describe(by_id[refs[0]])}"]
        lines += [f"  проверить {describe(by_id[ref])}" for ref in refs[1:]]
        groups.append("\n".join(lines))
    if not groups:
        return None
    what = f"рассказе «{r['title']}»" if collection else f"книге «{r['title']}»"
    prefix = r["candidates"][0].rstrip("0123456789")
    return VERIFY.format(what=what, p=prefix, groups="\n\n".join(groups))


def describe(c: dict) -> str:
    text = f"{c['id']} {c['display']} (упоминаний {c['count']}, род {c['gender']}, формы: {', '.join(list(c['forms'])[:4])})"
    for e in c["examples"][:3]:
        text += f"\n      пример: {e}"
    return text


def label_targets(r: dict, answer: dict, by_id: dict) -> list[tuple[str, str]]:
    """(id персонажа, кандидат) для каждого обращения или голой фамилии, которые LLM отнесла к персонажу."""
    out = []
    raw_characters = answer.get("characters", [])
    for raw in raw_characters if isinstance(raw_characters, list) else []:
        if not isinstance(raw, dict):
            continue
        refs = character_refs(raw, r, by_id)
        for ref in refs:
            if len(by_id[ref].get("contexts") or []) < MIN_LABEL_ANSWERS:
                continue
            c = by_id[ref]
            if c["kind"] != "title" and len(refs) == 1 and not any(
                    by_id[x]["kind"] == "name" and len(by_id[x]["key"].split()) > 1 and by_id[x]["key"].split()[-1] == c["key"]
                    for x in r["candidates"] if x != ref):
                continue  # единственное имя персонажа и других носителей фамилии нет: путать не с кем
            out.append((str(raw.get("id", "")), ref))
    # Обращение, которое LLM оставила в «прочих» («генерал» — то один, то другой): в отдельных главах
    # оно может быть однозначным — его тоже проверяем по отрывкам.
    other = answer.get("other", [])
    for ref in dict.fromkeys(other if isinstance(other, list) else []):
        c = by_id.get(ref) if isinstance(ref, str) and ref in r["candidates"] else None
        if c and c["kind"] == "title" and len(c.get("contexts") or []) >= MIN_LABEL_ANSWERS and c["speaker"] >= 2:
            out.append(("", ref))
    return out


def label_requests(r: dict, answer: dict, by_id: dict, ref: str, collection: bool) -> list[tuple[str, str, int, int]]:
    """(суффикс имени файла, запрос, начало, конец) — отрывки порциями по LABEL_BATCH."""
    total = len(by_id[ref]["contexts"])
    where = by_id[ref].get("context_sections") or []
    if by_id[ref]["kind"] == "title" and len(set(where)) > 1 and len(where) == total:
        # Обращение в романе — отдельный запрос на главу: «молодой чиновник, по фамилии Фердыщенко» из одной
        # главы не должен подсказать ответ для «чиновника» другой главы.
        parts, lo = [], 0
        for i in range(1, total + 1):
            if i == total or where[i] != where[lo] or i - lo >= LABEL_BATCH:
                parts.append((lo, i))
                lo = i
    else:
        parts = [(lo, min(total, lo + LABEL_BATCH)) for lo in range(0, total, LABEL_BATCH)]
    return [(f".label.{ref}" if len(parts) == 1 else f".label.{ref}.{n}", label_prompt(r, answer, by_id, ref, collection, lo, hi), lo, hi)
            for n, (lo, hi) in enumerate(parts, 1)]


def label_whos(folder: str, name: str, r: dict, answer: dict, by_id: dict, ref: str, collection: bool, meta_ok) -> list | None:
    """Ответ «кто это» для каждого отрывка по порядку; None — какой-то порции нет или она устарела."""
    whos = []
    for suffix, prompt, lo, hi in label_requests(r, answer, by_id, ref, collection):
        path = os.path.join(folder, name + suffix)
        if not meta_ok(load_answer(path + ".meta.json"), prompt):
            return None
        checked = load_answer(path + ".json")
        if label_votes(checked, hi - lo) is None:
            return None
        whos += [a["who"] for a in sorted(checked["answers"], key=lambda a: a["n"])]
    return whos


def label_prompt(r: dict, answer: dict, by_id: dict, ref: str, collection: bool, lo: int = 0, hi: int | None = None) -> str:
    people = []
    for raw in answer.get("characters", []):
        if not isinstance(raw, dict):
            continue
        forms = collections.Counter()
        for x in character_refs(raw, r, by_id):
            if x != ref:
                forms.update(by_id[x]["forms"])
        names = ", ".join(f for f, _ in forms.most_common(6))
        people.append(f"- {raw.get('id', '')}: {raw.get('name', '')}" + (f" ({names})" if names else ""))
    snippets = "\n".join(f"{n}. {text}" for n, text in enumerate(by_id[ref]["contexts"][lo:hi], 1))
    what = f"рассказа «{r['title']}»" if collection else f"книги «{r['title']}»"
    return LABELS.format(what=what, people="\n".join(people), label=by_id[ref]["display"], snippets=snippets)


def label_votes(checked: dict | None, count: int) -> collections.Counter | None:
    """Голоса «кто это» по отрывкам; None — ответ неполный или с повторами номеров."""
    if not valid_response(checked, kind="labels"):
        return None
    numbers = [a["n"] for a in checked["answers"]]
    if sorted(numbers) != list(range(1, count + 1)):
        return None
    return collections.Counter(a["who"] for a in checked["answers"])


def combine_answers(answers: list, r: dict, by_id: dict) -> dict:
    """Несколько независимых ответов → один: вместе только те, кого объединило большинство ответов.
    Сопоставление литературного текста у LLM меняется от запуска к запуску; голосование убирает случайные
    склейки и случайные потери."""
    if len(answers) == 1:
        return answers[0]
    need = len(answers) // 2 + 1
    own = set(r["candidates"])
    together, person = collections.Counter(), collections.Counter()
    runs = []
    for answer in answers:
        groups = []
        for rank, raw in enumerate(answer.get("characters", [])):
            if not isinstance(raw, dict):
                continue
            refs = [x for x in dict.fromkeys(raw.get("candidates", [])) if isinstance(x, str) and x in own]
            groups.append((rank, raw, refs))
            person.update(refs)
            for a in refs:
                for b in refs:
                    if a < b:
                        together[(a, b)] += 1
        runs.append(groups)
    parent = {x: x for x in own}

    def root(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    for (a, b), n in together.items():
        if n >= need and person[a] >= need and person[b] >= need:
            parent[root(a)] = root(b)
    components = collections.defaultdict(list)
    for x in r["candidates"]:
        if person[x] >= need:
            components[root(x)].append(x)
    entries = []
    for refs in components.values():
        ids, names, genders, ranks = collections.Counter(), collections.Counter(), collections.Counter(), []
        for groups in runs:
            hits = [(rank, raw) for rank, raw, members in groups if set(members) & set(refs)]
            ranks.append(min((rank for rank, _ in hits), default=len(groups)))
            for _, raw in hits:
                weight = len(set(refs) & set(next(m for rk, rw, m in groups if rw is raw)))
                ids[str(raw.get("id", ""))] += weight
                names[str(raw.get("name", ""))] += weight
                genders[raw.get("gender") if raw.get("gender") in ("m", "f", "?") else "?"] += weight
        entries.append({"rank": sum(ranks) / len(ranks), "ids": ids, "name": names.most_common(1)[0][0],
                        "gender": genders.most_common(1)[0][0], "candidates": refs})
    # Идентификатор — тот, что чаще всего давали этой группе; спорный достаётся группе с большим числом голосов.
    taken = {}
    for votes, n, ident in sorted(((v, n, ident) for n, e in enumerate(entries) for ident, v in e["ids"].items()), reverse=True):
        if ident not in taken.values() and n not in taken:
            taken[n] = ident
    result = []
    for n, e in sorted(enumerate(entries), key=lambda item: item[1]["rank"]):
        ident = taken.get(n) or f"person_{n + 1}"
        result.append({"id": ident, "name": e["name"], "gender": e["gender"], "candidates": e["candidates"]})
    placed = {x for ch in result for x in ch["candidates"]}
    return {"characters": result, "other": [x for x in r["candidates"] if x not in placed], "votes": len(answers)}


# ---------------------------------------------------------------- проверка ответа

def artifact_identity(folder: str) -> str:
    digest = hashlib.sha256()
    for name in ("candidates.json", "llm_request.json", "book_index.json"):
        path = os.path.join(folder, name)
        digest.update(name.encode())
        if os.path.isfile(path):
            with open(path, "rb") as f:
                digest.update(f.read())
    return digest.hexdigest()


def metadata_matches(meta: dict | None, prompt: str, folder: str) -> bool:
    if not meta or not all(k in meta for k in ("provider", "endpoint", "model", "thinking", "max_tokens", "request_sha256", "input_sha256", "done_reason")):
        return False
    if not all(isinstance(meta[k], str) for k in ("provider", "endpoint", "model", "request_sha256", "input_sha256", "done_reason")) or not isinstance(meta["thinking"], bool) or not isinstance(meta["max_tokens"], int):
        return False
    source = artifact_identity(folder)
    return meta["input_sha256"] == source and meta["done_reason"] != "length" and meta["request_sha256"] == cache_fingerprint(
        meta["provider"], meta["endpoint"], meta["model"], meta["thinking"], prompt, meta["max_tokens"], source)


def cache_fingerprint(provider: str, endpoint: str, model: str, think: bool, prompt: str, max_tokens: int, input_sha256: str = "") -> str:
    return hashlib.sha256(json.dumps(["cast-verify-v2", provider, endpoint.rstrip("/"), model,
                                     think, max_tokens, prompt, input_sha256], ensure_ascii=False).encode()).hexdigest()


def parse_answer(text: str) -> dict | None:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("повторный ключ JSON")
            result[key] = value
        return result
    start, end = text.find("{"), text.rfind("}")
    try:
        value = json.loads(text[start:end + 1], object_pairs_hook=unique) if 0 <= start < end else None
        return value if isinstance(value, dict) else None
    except (ValueError, TypeError):
        return None


GENDERS = {"m": "m", "м": "m", "male": "m", "masc": "m", "муж": "m", "мужской": "m",
           "f": "f", "ж": "f", "female": "f", "femn": "f", "жен": "f", "женский": "f"}


def normalize_answer(answer: dict | None) -> dict | None:
    """Мелкие отклонения формы не повод выбрасывать весь ответ: «м» → «m», номер числом → строкой."""
    if not isinstance(answer, dict):
        return answer
    for ch in answer.get("characters", []) if isinstance(answer.get("characters"), list) else []:
        if isinstance(ch, dict):
            if "gender" in ch:
                ch["gender"] = GENDERS.get(str(ch["gender"]).strip().lower(), "?")
            if isinstance(ch.get("id"), (int, float)):
                ch["id"] = str(ch["id"])
            if ch.get("name") is None:
                ch["name"] = ""
    for check in answer.get("checks", []) if isinstance(answer.get("checks"), list) else []:
        if isinstance(check, dict) and isinstance(check.get("verdict"), str):
            check["verdict"] = check["verdict"].strip().lower()
    for item in answer.get("answers", []) if isinstance(answer.get("answers"), list) else []:
        if isinstance(item, dict) and isinstance(item.get("n"), str) and item["n"].strip().isdigit():
            item["n"] = int(item["n"])
    return answer


def valid_response(answer: dict | None, verify: bool = False, kind: str = "") -> bool:
    if not isinstance(answer, dict):
        return False
    if kind == "labels":
        return isinstance(answer.get("answers"), list) and all(
            isinstance(a, dict) and isinstance(a.get("n"), int) and not isinstance(a.get("n"), bool)
            and isinstance(a.get("who"), str) for a in answer["answers"])
    if verify:
        return isinstance(answer.get("checks"), list) and all(isinstance(c, dict) and
            all(isinstance(c.get(k), str) for k in ("character", "anchor", "candidate")) and
            c.get("verdict") in ("same", "different", "unsure") for c in answer["checks"])
    return isinstance(answer.get("characters"), list) and isinstance(answer.get("other"), list) and all(
        isinstance(c, dict) and isinstance(c.get("id"), str) and isinstance(c.get("name"), str) and
        c.get("gender") in ("m", "f", "?") and isinstance(c.get("candidates"), list) and
        all(isinstance(ref, str) for ref in c["candidates"]) for c in answer["characters"]) and all(
        isinstance(ref, str) for ref in answer["other"])


def load_answer(path: str) -> dict | None:
    try:
        with open(path, encoding="utf-8") as f:
            return parse_answer(f.read())
    except (OSError, UnicodeError):
        return None


# Сколько главных героев (по порядку LLM) получают свой голос первыми, независимо от числа реплик.
PROTAGONISTS = 3
# Обращение или голая фамилия остаются у персонажа, если в его пользу не меньше этой доли упоминаний.
DOMINANCE = 0.8
PATRONYMIC_SHORT = ((r"ович$", "ыч"), (r"евич$", "ич"))


def label_decision(votes: collections.Counter, owner: str, minimum: int = MIN_LABEL_ANSWERS) -> tuple[bool, int, int]:
    """(оставить ли, в пользу owner, понятных ответов) по ответам «кто это» в отрывках."""
    known = sum(n for who, n in votes.items() if who != "unsure")
    own = votes.get(owner, 0)
    enough = known >= max(minimum, sum(votes.values()) / 2)
    return enough and own >= DOMINANCE * known, own, known


def title_plan(whos: list, where: list, sections: list, people: set, hint: str = "",
               sole_title_people: frozenset = frozenset()) -> tuple[dict, dict]:
    """Чьё обращение в каждой главе. Генерал может «кочевать»: в одних главах это Епанчин, в других Иволгин.
    Глава с уверенным большинством ответов — за этим человеком; с разногласием — «прочие»; с одним-двумя
    упоминаниями — как соседние главы до и после, если они решены одинаково, иначе как вся книга.
    Возвращает ({глава: id или None}, сведения для отчёта)."""
    def top(votes):
        named = [(n, who) for who, n in votes.items() if who in people]
        return max(named)[1] if named else None

    every = collections.Counter(whos)
    best = top(every)
    # Основной разбор отдал обращение другому — оно остаётся спорным, кроме случая, когда тот «другой» —
    # персонаж из одного этого обращения («начальник»), а отрывки, где он был в списке, назвали другого.
    agrees = not hint or hint == best or hint in sole_title_people
    whole = best if best and label_decision(every, best)[0] and agrees else None
    state = {}
    for sid in sections:
        votes = collections.Counter(who for who, w in zip(whos, where) if w == sid)
        if not votes:
            state[sid] = ("none", None)
            continue
        lead = top(votes)
        keep, own, known = label_decision(votes, lead, MIN_CHAPTER_ANSWERS) if lead else (False, 0, 0)
        if keep and hint and lead != hint and hint not in sole_title_people:
            keep, own = False, 0  # отрывки против основного разбора: не перевешиваем, а оставляем в «прочих»
        rivals = sum(n for who, n in votes.items() if who in people and who != lead)
        if not keep and lead and lead == hint and not rivals and known >= MIN_CHAPTER_ANSWERS and own >= AGREED_DOMINANCE * known:
            keep = True  # «чиновник» в 1-й главе: основной разбор — Лебедев, отрывки — Лебедев 2, «не знаю» 1
        state[sid] = ("sure", lead) if keep else ("few", None) if known < MIN_CHAPTER_ANSWERS else ("split", None)
    sure = [(i, state[sid][1]) for i, sid in enumerate(sections) if state[sid][0] == "sure"]
    plan = {}
    for i, sid in enumerate(sections):
        kind, owner = state[sid]
        if kind == "sure":
            plan[sid] = owner
        elif kind == "split":
            plan[sid] = None
        elif kind == "few":
            before = next((o for j, o in reversed(sure) if j < i), None)
            after = next((o for j, o in sure if j > i), None)
            plan[sid] = before if before and before == after else whole
        else:
            plan[sid] = whole
    info = {"votes": dict(every.most_common()), "whole": whole,
            "chapters": {sid: (state[sid][0], plan[sid]) for sid in sections if state[sid][0] != "none"}}
    return plan, info


def name_words(key: str) -> set:
    words = set()
    for word in key.split():
        for pattern, short in PATRONYMIC_SHORT:
            word = re.sub(pattern, short, word)
        words.add(word)
    return words


def merge_accepted(verdict, anchor: str, ref: str, group: list, own_ids: set, candidates: dict) -> bool:
    """Склейку разрешает «same» второго запроса. «unsure» — только если короткое имя целиком входит в полное
    («Аглая» ⊂ «Аглая Ивановна», «Евгений Павлыч» ⊂ «Евгений Павлович Радомский») и больше ни в чьё."""
    if verdict == "same":
        return True
    if verdict != "unsure":
        return False
    a, b = candidates[anchor], candidates[ref]
    if a["kind"] != "name" or b["kind"] != "name":
        return False
    wa, wb = name_words(a["key"]), name_words(b["key"])
    short, long_ = (wa, wb) if len(wa) <= len(wb) else (wb, wa)
    if not short or not short < long_ or (a["gender"] in "mf" and b["gender"] in "mf" and a["gender"] != b["gender"]):
        return False
    return not any(short <= name_words(candidates[x]["key"]) for x in own_ids
                   if x not in group and candidates[x]["kind"] == "name")


def build_cast(r: dict, answer: dict | None, verdicts: dict | None, candidates: dict, prefix: str,
               labels: dict | None = None) -> dict:
    """Проверенный ответ: каждый кандидат ровно у одного персонажа или в «прочих». К персонажу остаются
    только подтверждённые второй проверкой («same») и не противоречащие ему по роду; остальное — «прочие»."""
    problems: list[str] = []
    dropped: list[str] = []
    own_ids = set(r["candidates"])
    used: dict[str, str] = {}
    characters = []
    if answer is None:
        problems.append("нет ответа LLM или в нём нет JSON — все кандидаты в «прочих»")
        answer = {}
    raw_characters = answer.get("characters", [])
    if not isinstance(raw_characters, list):
        raw_characters = []
        problems.append("characters должен быть списком — все кандидаты в прочих")
    ownership = collections.Counter(ref for raw in raw_characters if isinstance(raw, dict)
        for ref in (raw.get("candidates", []) if isinstance(raw.get("candidates", []), list) else [])
        if isinstance(ref, str) and ref in own_ids)
    explicit_other = answer.get("other", [])
    if not isinstance(explicit_other, list):
        explicit_other = []
    ownership.update(ref for ref in explicit_other if isinstance(ref, str) and ref in own_ids)
    conflicts = {ref for ref, count in ownership.items() if count > 1}
    if conflicts:
        problems.append("повторные кандидаты → прочие: " + ", ".join(sorted(conflicts)))
    for priority, raw in enumerate(raw_characters):
        if not isinstance(raw, dict):
            problems.append("персонаж должен быть объектом — пропущен")
            continue
        cid = re.sub(r"[^a-z0-9_]", "_", str(raw.get("id", "")).lower()).strip("_")
        cid = prefix + cid if cid else ""
        if not cid or cid.endswith(("author", "other")) or any(ch["id"] == cid for ch in characters):
            problems.append(f"идентификатор «{raw.get('id')}» пустой, служебный или повторяется — персонаж пропущен")
            continue
        own = []
        gender = raw.get("gender") if raw.get("gender") in ("m", "f", "?") else "?"
        refs = character_refs(raw, r, candidates)
        if not refs:
            problems.append(f"{cid}: нет допустимых кандидатов — пропущен")
            continue
        anchor = refs[0]
        for ref in refs:
            candidate = candidates[ref]
            if ref in conflicts:
                dropped.append(f"{ref} {candidate['display']} → прочие: несколько владельцев")
            elif candidate["kind"] == "family":
                dropped.append(f"{ref} {candidate['display']} → прочие: семья, не один человек")
            elif gender in ("m", "f") and candidate["gender"] in ("m", "f") and candidate["gender"] != gender \
                    and candidate.get("gender_source") in ("verb", "Patr", "Title"):
                dropped.append(f"{ref} {candidate['display']} → прочие: род {candidate['gender']}, у {cid} {gender}")
            elif ref != anchor and (anchor not in own or not verdicts or not merge_accepted(
                    verdicts.get((str(raw.get("id", "")), anchor, ref)), anchor, ref, refs, own_ids, candidates)):
                dropped.append(f"{ref} {candidate['display']} → прочие: склейка с {anchor} не подтверждена для {cid}")
            else:
                used[ref] = cid
                own.append(ref)
        if not own:
            problems.append(f"{cid}: нет ни одного кандидата — пропущен")
            continue
        characters.append({"id": cid, "name": str(raw.get("name") or ""), "gender": gender,
                           "priority": priority, "candidates": own, "source_id": str(raw.get("id", ""))})
    labels = labels or {}

    def drop_with_dependents(ch: dict, ref: str, reason: str) -> None:
        was_anchor = ch["candidates"][0] == ref
        ch["candidates"].remove(ref)
        used[ref] = "other"
        dropped.append(f"{ref} {candidates[ref]['display']} → прочие: {reason}")
        if was_anchor and candidates[ref]["kind"] == "name":
            for dependent in ch["candidates"]:
                used[dependent] = "other"
                dropped.append(f"{dependent} {candidates[dependent]['display']} → прочие: склейка зависит от неоднозначной формы {ref}")
            ch["candidates"] = []

    # Обращение или голая фамилия, проверенные по отрывкам всей книги: остаются, только если почти везде это он.
    for ch in characters:
        for ref in list(ch["candidates"]):
            votes = labels.get((ch["source_id"], ref))
            if votes is None or ref not in ch["candidates"]:
                continue
            keep, own, known = label_decision(votes, ch["source_id"])
            if keep:
                continue  # и персонаж из одного обращения («мама», «бабушка»), если отрывки подтвердили его
            rivals = ", ".join(f"{who} {n}" for who, n in votes.most_common() if who != ch["source_id"])
            drop_with_dependents(ch, ref, f"в отрывках это {ch['id']} {own} из {known} понятных"
                                 + (f" (ещё: {rivals})" if rivals else ""))
    # A bare surname shared by independently named relatives is also ambiguous.
    # This does not reassign the surname: it preserves their full, distinct names.
    for ch in characters:
        for ref in list(ch["candidates"]):
            candidate = candidates[ref]
            if (ch["source_id"], ref) in labels or ref not in ch["candidates"]:
                continue
            if candidate["kind"] != "name" or len(candidate["key"].split()) != 1 or not candidate.get("roles", {}).get("Surn"):
                continue
            relatives = [x for x in own_ids if candidates[x]["kind"] == "name"
                         and len(candidates[x]["key"].split()) > 1
                         and candidates[x]["key"].split()[-1] == candidate["key"]
                         and used.get(x, "other") != ch["id"]]
            if not relatives:
                continue
            # «Рогожин» 26 раз, отец «Семен Парфенович Рогожин» — 2: фамилия остаётся у Парфена.
            # «Иволгин» у отца и сына, оба часто названы полностью — фамилия неоднозначна.
            rival = sum(candidates[x]["count"] for x in relatives)
            if candidate["count"] >= DOMINANCE * (candidate["count"] + rival):
                continue
            drop_with_dependents(ch, ref, f"фамилия встречается в других полных именах ({rival} упоминаний против {candidate['count']})")
    # Any named candidate can contradict a title, even if the LLM omitted that person.
    for ch in characters:
        for ref in [ref for ref in ch["candidates"] if candidates[ref]["kind"] == "title"]:
            if (ch["source_id"], ref) in labels:
                continue
            word = candidates[ref]["key"]
            per = {other["id"]: sum(candidates[x]["titles"].get(word, 0) for x in other["candidates"] if x != ref) for other in characters}
            unassigned = sum(candidates[x]["titles"].get(word, 0) for x in own_ids
                             if candidates[x]["kind"] == "name" and used.get(x, "other") == "other")
            total = sum(per.values()) + unassigned
            if (total > 0 and per[ch["id"]] < total) or (total == 0 and len(ch["candidates"]) == 1):
                ch["candidates"].remove(ref)
                used[ref] = "other"
                other_owner = max(per, key=per.get) if total else None
                dropped.append(f"{ref} {candidates[ref]['display']} → прочие: обращение связано с именем {ch['id']} "
                               f"{per[ch['id']]} из {total} раз" + (f" (чаще {other_owner})" if other_owner and other_owner != ch["id"] else ""))
    characters = [ch for ch in characters if ch["candidates"]]
    for ch in characters:
        # Имя только из слов книги: «Аглая Ивановна Епанчина» (все слова найдены) — да; «Анастасия (Настенька)»,
        # если «Анастасии» в книге нет, — нет. Тогда самая полная найденная форма в именительном падеже.
        words = {w for ref in ch["candidates"] for w in candidates[ref]["key"].split()}
        name_words = [w.lower().replace("ё", "е") for w in re.findall(r"[А-ЯЁа-яё-]+", ch["name"])]
        if not name_words or any(w not in words for w in name_words) or re.search(r"[()]", ch["name"]):
            longest = max((candidates[ref] for ref in ch["candidates"] if candidates[ref]["kind"] == "name"),
                          key=lambda c: (len(c["key"].split()), c["count"]), default=candidates[ch["candidates"][0]])
            ch["name"] = " ".join(w.capitalize() if longest["kind"] == "name" else w for w in longest["key"].split())
    other = []
    for ref in explicit_other:
        if isinstance(ref, str) and ref in own_ids and ref not in used:
            used[ref] = "other"
            other.append(ref)
    for line in dropped:
        ref = line.split()[0]
        if used.get(ref, "other") == "other" and ref not in other:
            used[ref] = "other"
            other.append(ref)
    missing = [ref for ref in r["candidates"] if ref not in used]
    if missing:
        problems.append(f"не распределено {len(missing)} — отнесены к «прочим»: {', '.join(missing[:15])}")
        other += missing
    alias: dict[str, set] = collections.defaultdict(set)
    scope = set(r["sections"])

    def within(c: dict, total: str, per_section: str) -> int:
        counts = c.get(per_section)
        return sum(n for sid, n in counts.items() if sid in scope) if isinstance(counts, dict) else c[total]
    for ch in characters:
        forms = collections.Counter()
        ch["mentions"] = sum(within(candidates[ref], "count", "section_counts") for ref in ch["candidates"])
        ch["speaker"] = sum(within(candidates[ref], "speaker", "speaker_sections") for ref in ch["candidates"])
        for ref in ch["candidates"]:
            forms.update(candidates[ref]["forms"])
            alias[candidates[ref]["key"]].add(ch["id"])
            for form in candidates[ref]["forms"]:
                alias[form.lower().replace("ё", "е")].add(ch["id"])
        ch["forms"] = [f for f, _ in forms.most_common()]
    for ref in other:
        alias[candidates[ref]["key"]].add("other")
        for form in candidates[ref]["forms"]:
            alias[form.lower().replace("ё", "е")].add("other")
    characters.sort(key=lambda ch: (ch["priority"], ch["id"]))
    return {
        "sections": r["sections"], "title": r["title"], "characters": characters,
        "other": [{"candidate": ref, "display": candidates[ref]["display"], "count": candidates[ref]["count"]} for ref in other],
        # Форма → персонаж. Если форма у нескольких (отец и сын «Иволгин»), читалка решает по контексту,
        # а без него читает голосом «прочих».
        "alias_index": {k: next(iter(v)) for k, v in sorted(alias.items()) if len(v) == 1},
        "ambiguous": {k: sorted(v) for k, v in sorted(alias.items()) if len(v) > 1},
        "problems": problems,
        "dropped": dropped,
    }


def apply(args) -> None:
    data = read_json(os.path.join(args.dir, "candidates.json"))
    request = read_json(os.path.join(args.dir, "llm_request.json"))
    candidates = {c["id"]: c for c in data["candidates"]}
    collection = request["scope"] == "section"
    casts = []
    for r in request["requests"]:
        answer = load_answer(os.path.join(args.dir, "answers", r["name"] + ".json"))
        main_meta = load_answer(os.path.join(args.dir, "answers", r["name"] + ".meta.json"))
        if not metadata_matches(main_meta, r["prompt"], args.dir) or not valid_response(answer):
            raise ValueError(f"[{r['name']}] ответ устарел или не проверен: выполните llm перед apply")
        checked = load_answer(os.path.join(args.dir, "answers", r["name"] + ".verify.json"))
        check_prompt = verify_prompt(r, answer, candidates, collection)
        check_meta = load_answer(os.path.join(args.dir, "answers", r["name"] + ".verify.meta.json"))
        if check_prompt and not metadata_matches(check_meta, check_prompt, args.dir):
            checked = None
        verdicts = verification_verdicts(checked, answer, r, candidates)
        if verdicts is None and answer and any(len(ch.get("candidates", [])) > 1 for ch in answer.get("characters", [])):
            verdicts = {}  # склейки без второй проверки не принимаются
        labels, plans, report = {}, {}, {}
        people = {str(ch.get("id", "")) for ch in answer.get("characters", []) if isinstance(ch, dict)}
        folder = os.path.join(args.dir, "answers")
        for raw_id, ref in label_targets(r, answer, candidates):
            whos = label_whos(folder, r["name"], r, answer, candidates, ref, collection,
                              lambda meta, prompt: metadata_matches(meta, prompt, args.dir))
            if whos is None:
                continue  # нет ответа по отрывкам — действуют прежние строгие правила
            if candidates[ref]["kind"] == "title":
                where = candidates[ref].get("context_sections") or [r["sections"][0]] * len(whos)
                sole = frozenset(str(ch.get("id", "")) for ch in answer.get("characters", []) if isinstance(ch, dict)
                                 and [x for x in ch.get("candidates", []) if x in candidates] == [ref])
                plans[ref], report[ref] = title_plan(whos, where, r["sections"], people, raw_id, sole)
            elif raw_id:
                labels[(raw_id, ref)] = collections.Counter(whos)
                report[ref] = {"votes": dict(collections.Counter(whos).most_common()), "whole": raw_id}
        # Главы с одинаковыми решениями по обращениям — один набор персонажей.
        groups: dict[tuple, list] = {}
        for sid in r["sections"]:
            groups.setdefault(tuple((ref, plans[ref][sid]) for ref in sorted(plans)), []).append(sid)
        for signature, sids in groups.items():
            variant = json.loads(json.dumps(answer))
            variant_verdicts = dict(verdicts or {})
            variant_labels = dict(labels)
            for ref, owner in signature:
                for ch in variant.get("characters", []):
                    if isinstance(ch, dict) and isinstance(ch.get("candidates"), list):
                        ch["candidates"] = [x for x in ch["candidates"] if x != ref]
                variant["other"] = [x for x in variant.get("other", []) if x != ref]
                raw = next((ch for ch in variant.get("characters", []) if isinstance(ch, dict) and str(ch.get("id", "")) == owner), None)
                if raw is None:
                    variant["other"].append(ref)
                    continue
                raw["candidates"].append(ref)
                anchor = character_refs(raw, r, candidates)[0]
                if anchor != ref:
                    variant_verdicts[(owner, anchor, ref)] = "same"  # подтверждено отрывками этой главы
                variant_labels[(owner, ref)] = collections.Counter({owner: MIN_LABEL_ANSWERS})
            cast = build_cast(dict(r, sections=sids), variant, variant_verdicts, candidates,
                              r["name"] + "." if collection else "", variant_labels)
            cast["label_report"] = {candidates[ref]["display"]: info for ref, info in report.items()}
            cast["request"] = r["name"]
            casts.append(cast)
    section_cast = {sid: i for i, cast in enumerate(casts) for sid in cast["sections"]}
    save(args.dir, "cast.json", {
        "book": data["book"], "scope": request["scope"], "input_sha256": artifact_identity(args.dir), "narrator": "author", "others": "other",
        "sections": [dict(s, cast=section_cast.get(s["id"])) for s in data["sections"]], "casts": casts})
    total = sum(len(c["characters"]) for c in casts)
    print(f"«{data['book']}»: наборов {len(casts)}, персонажей {total}, "
          f"в «прочих» {sum(len(c['other']) for c in casts)}, снято проверкой {sum(len(c['dropped']) for c in casts)}, "
          f"замечаний {sum(len(c['problems']) for c in casts)}")
    for cast in casts[: args.show_casts]:
        print(f"  [{','.join(cast['sections'][:3])}{'…' if len(cast['sections']) > 3 else ''}] {cast['title']}")
        for ch in cast["characters"][: args.show]:
            print(f"     {ch['id']:<28} {ch['name']:<32} {ch['gender']} упоминаний={ch['mentions']:<4} реплик={ch['speaker']:<3} "
                  f"{', '.join(ch['forms'][:5])}")
        for p in cast["dropped"]:
            print("     - " + p)
        for p in cast["problems"]:
            print("     ! " + p)


# ---------------------------------------------------------------- голоса

def voices(args) -> None:
    """Свой голос — главным: тем, кто говорит (реплики в ремарках) или часто упоминается; по полу, по одному
    на персонажа. Когда голоса нужного пола кончились — общий голос с тем, с кем персонаж почти не встречается
    в одних абзацах. Остальные (второстепенные, молчащие, без пола) — голос «прочих» своего пола или автора."""
    cast = read_json(os.path.join(args.dir, "cast.json"))
    if cast.get("input_sha256") != artifact_identity(args.dir):
        raise ValueError("cast.json от другого извлечения: повторите llm и apply")
    candidates = {c["id"]: c for c in read_json(os.path.join(args.dir, "candidates.json"))["candidates"]}
    spec = read_json(args.voices)
    reserved = {spec["narrator"], spec["other_m"], spec["other_f"]}
    pool = {g: [v["id"] for v in spec["voices"] if v["gender"] == g and v["id"] not in reserved] for g in ("m", "f")}
    unknown = [v["id"] for v in spec["voices"] if v["gender"] not in ("m", "f")]

    def meetings(a: dict, b: dict) -> int:
        return sum(candidates[x].get("together_counts", {}).get(y, 0) for x in a["candidates"] for y in b["candidates"])

    # Роман по главам — несколько наборов с одними персонажами (разное только «чей генерал»): голоса раздаются
    # один раз на всех, по самому полному виду каждого персонажа, — один человек звучит одинаково во всех главах.
    families: dict[str, list] = collections.defaultdict(list)
    for n, group in enumerate(cast["casts"]):
        families[group.get("request", str(n))].append(group)
    for family in families.values():
        union: dict[str, dict] = {}
        for group in family:  # наборы делят главы между собой: реплики и упоминания складываются
            for ch in group["characters"]:
                seen = union.get(ch["id"])
                if seen is None:
                    union[ch["id"]] = dict(ch, candidates=sorted(set(ch.get("candidates", []))))
                else:
                    seen["speaker"] += ch["speaker"]
                    seen["mentions"] += ch["mentions"]
                    seen["candidates"] = sorted(set(seen["candidates"]) | set(ch.get("candidates", [])))
        group = {"characters": list(union.values())}
        # Rejected ambiguous titles must not erase the protagonist's voice priority.
        # Legacy casts without model priority keep their previous ordering.
        # Главные герои по оценке LLM — первыми (их голос не теряется, даже если обращение «князь» снято);
        # остальным голосам — тем, кто больше говорит: своих голосов мало, а нужнее всего они говорящим.
        by_rank = sorted(group["characters"], key=lambda ch: (ch.get("priority", len(group["characters"])),
                         -ch["speaker"], -ch["mentions"], ch["id"]))
        leads = by_rank[:PROTAGONISTS]
        order = leads + sorted(by_rank[PROTAGONISTS:], key=lambda ch: (-ch["speaker"], -ch["mentions"], ch["id"]))
        holders: dict[str, list] = collections.defaultdict(list)
        lead_ids = {ch["id"] for ch in leads}
        for ch in order:
            # Часто упоминаемый без реплик — свой голос только у главных героев: исторические лица
            # научно-популярной книги («Конфуций», «Мао Цзэдун») не говорят, и голос им не нужен.
            main = ch["gender"] in ("m", "f") and (ch["speaker"] >= args.min_speaker or (
                ch["mentions"] >= args.min_mentions and (ch["speaker"] > 0 or ch["id"] in lead_ids)))
            if not main or not pool.get(ch["gender"]):
                ch["voice"] = spec["other_" + ch["gender"]] if ch["gender"] in ("m", "f") else spec["narrator"]
                ch["role"] = "other"
                continue
            free = [v for v in pool[ch["gender"]] if not holders[v]]
            if free:
                voice, role = free[0], "own"
            else:
                # Голос того, с кем меньше всего общих абзацев; слишком часто вместе — в «прочие».
                voice = min(pool[ch["gender"]], key=lambda v: max(meetings(ch, other) for other in holders[v]))
                shared = max(meetings(ch, other) for other in holders[voice])
                role = "shared" if shared <= args.max_shared else "other"
                if role == "other":
                    voice = spec["other_" + ch["gender"]]
            ch["voice"], ch["role"] = voice, role
            if role != "other":
                holders[voice].append(ch)
        for member in family:
            for ch in member["characters"]:
                ch["voice"], ch["role"] = union[ch["id"]]["voice"], union[ch["id"]]["role"]
            member["voices"] = {"narrator": spec["narrator"], "other_m": spec["other_m"], "other_f": spec["other_f"]}
    cast["voice_model"] = spec.get("model", "")
    save(args.dir, "cast.json", cast)
    roles = collections.Counter({(g.get("request"), ch["id"]): ch["role"] for g in cast["casts"] for ch in g["characters"]}.values())
    print(f"«{cast['book']}»: свой голос {roles['own']}, общий {roles['shared']}, «прочие» {roles['other']}; "
          f"голосов: мужских {len(pool['m'])}, женских {len(pool['f'])} (+ автор {spec['narrator']}, прочие "
          f"{spec['other_m']}/{spec['other_f']}), без пола не раздаются: {', '.join(unknown) or 'нет'}")
    for group in cast["casts"][: args.show_casts]:
        print(f"  [{','.join(group['sections'][:3])}{'…' if len(group['sections']) > 3 else ''}] {group['title']}")
        for ch in sorted(group["characters"], key=lambda ch: (ch["role"] == "other", -ch["speaker"], -ch["mentions"]))[: args.show]:
            print(f"     {ch['voice']:<14} {ch['role']:<6} {ch['name']:<32} {ch['gender']} реплик={ch['speaker']:<3} упоминаний={ch['mentions']}")


# ---------------------------------------------------------------- обмен

def export(args) -> None:
    """cast.json + отпечатки → .mytts-book: без текста книги и примеров, можно передавать другим."""
    cast = read_json(os.path.join(args.dir, "cast.json"))
    if cast.get("input_sha256") != artifact_identity(args.dir):
        raise ValueError("cast.json от другого извлечения: повторите llm и apply")
    index = read_json(os.path.join(args.dir, "book_index.json"))
    if "sentences" not in index:
        sys.exit("book_index.json старого формата: повторите extract")
    by_section: dict[str, list] = collections.defaultdict(list)
    for fp, sid in sorted(index["sentences"].items()):
        by_section[sid].append(fp)
    casts = []
    for group in cast["casts"]:
        characters = []
        for ch in group["characters"]:
            if ch.get("role") == "other" and not args.keep_other:
                continue
            item = {"id": ch["id"], "name": ch["name"], "gender": ch["gender"], "speaker": ch["speaker"],
                    "mentions": ch["mentions"], "forms": ch["forms"][: args.max_forms]}
            if ch.get("voice") and ch.get("role") in ("own", "shared"):
                item["voice_hint"] = ch["voice"]
            characters.append(item)
        ambiguous = set(group.get("ambiguous", {}))
        for item in characters:
            item["forms"] = [form for form in item["forms"] if form.lower().replace("ё", "е") not in ambiguous]
        # Unrecognized aliases stay explicitly in «прочие», including their case forms.
        candidates = {c["id"]: c for c in read_json(os.path.join(args.dir, "candidates.json"))["candidates"]}
        other = []
        for entry in group["other"]:
            candidate = candidates[entry["candidate"]]
            other += [entry["display"], candidate["key"]] + list(candidate["forms"])[:args.max_forms]
        for ch in group["characters"]:
            if ch.get("role") == "other" and not args.keep_other:
                other += [ch["name"]] + ch["forms"][:args.max_forms]
        other = list(dict.fromkeys(other + sorted(ambiguous)))
        casts.append({"sections": group["sections"], "characters": characters, "other": other})
    data = {
        "format": "mytts-book", "version": 1, "book": index["book"], "scope": cast["scope"],
        "voice_model": cast.get("voice_model", ""),
        "sections": [{"id": s["id"], "title": s["title"], "cast": s.get("cast")} for s in cast["sections"]],
        "casts": casts, "fingerprint": index["fingerprint"], "fingerprints": dict(by_section),
    }
    target = args.output or os.path.join(args.dir, re.sub(r"[^\w.-]+", "_", index["book"]["title"]) + ".mytts-book")
    save(os.path.dirname(os.path.abspath(target)), os.path.basename(target), data, compact=True)
    write_report(args.dir, cast)
    total = sum(len(v) for v in by_section.values())
    print(f"{target}: персонажей {len({(c.get('request'), ch['id']) for c in cast['casts'] for ch in c['characters'] if ch.get('role') != 'other' or args.keep_other})}, "
          f"наборов {len(casts)}, разделов {len(data['sections'])}, "
          f"отпечатков {total}, {os.path.getsize(target) / 1024:.0f} КБ")


def write_report(folder: str, cast: dict) -> None:
    """отчёт.txt: кто каким голосом, что ушло в «прочие» и почему, чьи обращения в каких главах —
    чтобы за минуту проверить книгу глазами перед загрузкой в телефон."""
    titles = {s["id"]: s.get("title", s["id"]) for s in cast.get("sections", [])}
    lines = [f"«{cast.get('book', '')}» — {'сборник' if cast.get('scope') == 'section' else 'роман'}, разделов {len(titles)}", ""]
    families: dict[str, list] = collections.defaultdict(list)
    for n, group in enumerate(cast["casts"]):
        families[group.get("request", str(n))].append(group)
    for family in families.values():
        first = family[0]
        lines.append("=" * 72)
        lines.append(first.get("title", "") + (f" (наборов по главам: {len(family)})" if len(family) > 1 else ""))
        union = {}
        for group in family:
            for ch in group["characters"]:
                if ch["id"] not in union:
                    union[ch["id"]] = dict(ch)
                else:
                    union[ch["id"]]["speaker"] = union[ch["id"]].get("speaker", 0) + ch.get("speaker", 0)
                    union[ch["id"]]["mentions"] = union[ch["id"]].get("mentions", 0) + ch.get("mentions", 0)
        role_name = {"own": "свой голос", "shared": "общий голос", "other": "голос «прочих»"}
        for ch in sorted(union.values(), key=lambda ch: (ch.get("role") == "other", -ch.get("speaker", 0), -ch.get("mentions", 0))):
            lines.append(f"  {ch.get('name', ch['id']):<34} {ch.get('gender', '?')}  {role_name.get(ch.get('role'), '?'):<15} "
                         f"{ch.get('voice', ''):<13} реплик {ch.get('speaker', 0):<4} упоминаний {ch.get('mentions', 0):<5} "
                         f"{', '.join(ch.get('forms', [])[:6])}")
        reports = first.get("label_report") or {}
        if reports:
            lines.append("  Обращения и фамилии по отрывкам (кто это):")
            for label, info in reports.items():
                votes = ", ".join(f"{who} {n}" for who, n in list(info.get("votes", {}).items())[:4])
                lines.append(f"    «{label}»: {votes}; на всю книгу — {info.get('whole') or 'прочие'}")
                chapters = info.get("chapters") or {}
                changes = [(sid, owner) for sid, (state, owner) in chapters.items() if owner != info.get("whole")]
                for sid, owner in changes[:30]:
                    lines.append(f"       {titles.get(sid, sid)}: {owner or 'прочие'}")
        dropped = list(dict.fromkeys(line for group in family for line in group.get("dropped", [])))
        if dropped:
            lines.append("  Снято проверкой:")
            lines += [f"    {line}" for line in dropped]
        other = {}
        for group in family:
            for o in group.get("other", []):
                other[o["candidate"]] = o
        if other:
            top = sorted(other.values(), key=lambda o: -o.get("count", 0))[:25]
            lines.append("  В «прочих» (самые частые): " + ", ".join(f"{o.get('display', '')} ({o.get('count', 0)})" for o in top))
        problems = list(dict.fromkeys(p for group in family for p in group.get("problems", [])))
        if problems:
            lines.append("  Замечания: " + "; ".join(problems))
        lines.append("")
    with open(os.path.join(folder, "отчёт.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def vectors(args) -> None:
    """Тест-векторы отпечатков для Kotlin (books/BookFingerprint.kt)."""
    samples = [
        "— Да, князь, — сказал Рогожин. Он помолчал и прибавил: «Ёлки-палки, вот так встреча!»",
        "Князь Лев Николаевич Мы́шкин вошёл в гостиную; Настасья Филипповна обернулась к нему.",
        "Коротко. Совсем коротко! Но вот это предложение уже достаточно длинное для отпечатка?..",
        "В 1867 году в Петербурге было сыро и мокро… Поезд подходил к Варшавскому вокзалу.",
        "Mixed text with Latin words, digits 42 and русские слова\u00a0вместе — проверка нормализации.",
        "Неразрывный пробел после точки тоже граница.\u00a0Второе предложение начинается сразу после него!",
    ]
    data = [{"text": t, "letters": [letters(x)[:48] for x in SENTENCES.split(t)], "fingerprints": sentence_fingerprints(t)} for t in samples]
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print(f"{args.output}: {sum(len(d['fingerprints']) for d in data)} отпечатков")


# ---------------------------------------------------------------- всё сразу

def process(args) -> None:
    """EPUB → .mytts-book: extract, llm, apply, voices, export с настройками по умолчанию."""
    started = time.time()
    stem = re.sub(r"[^\w.-]+", "_", os.path.splitext(os.path.basename(args.book))[0])[:80]
    out = args.out or os.path.join("out", stem)
    step = lambda title: print(f"\n== {title}", flush=True)
    step("1/5 Кандидаты в персонажи")
    extract(Namespace(book=args.book, out=out, scope=args.scope, min_count=2, max_candidates=200, show=8, show_requests=5))
    step(f"2/5 Сопоставление в LLM ({args.provider})")
    llm(Namespace(dir=out, provider=args.provider, model=args.model, endpoint=args.endpoint, parallel=args.parallel,
                  redo=args.redo, timeout=args.timeout, max_tokens=args.max_tokens, think=args.think, votes=args.votes))
    step("3/5 Проверка ответа")
    apply(Namespace(dir=out, show=12, show_casts=3))
    step("4/5 Голоса")
    voices(Namespace(dir=out, voices=args.voices, min_speaker=2, min_mentions=30, max_shared=2, show=12, show_casts=3))
    step("5/5 Файл для MyTTS")
    export(Namespace(dir=out, output=args.output, max_forms=24, keep_other=False))
    print(f"\nГотово за {time.time() - started:.0f} с. Перенесите файл .mytts-book на телефон и загрузите его в MyTTS: "
          f"настройки LLM → мультиголос → «Книги с голосами персонажей» → «Загрузить файл книги».")


def add_llm_options(parser) -> None:
    parser.add_argument("--provider", choices=sorted(PROVIDERS), default="ollama", help="ollama (Ollama Cloud) или deepseek (API DeepSeek)")
    parser.add_argument("--model", help="по умолчанию: ollama — deepseek-v4.1-flash, deepseek — deepseek-flash")
    parser.add_argument("--endpoint", help="адрес сервиса, если не стандартный (например, свой сервер Ollama)")
    parser.add_argument("--parallel", type=int, default=1, help="одновременных запросов (по умолчанию по одному)")
    parser.add_argument("--redo", action="store_true", help="спросить заново и те разделы, на которые ответ уже есть")
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--votes", type=int, default=3, help="независимых основных ответов LLM, склейка — по большинству (по умолчанию 3)")
    parser.add_argument("--max-tokens", type=int, help="предел ответа: 16000, с --think 80000 (размышление входит в предел)")
    # Thinking improves recall in our samples but is not a correctness guarantee.
    parser.add_argument("--no-think", dest="think", action="store_false", help="выключить размышление (по умолчанию включено)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    pr = sub.add_parser("process", help="всё сразу: EPUB → файл .mytts-book для импорта в MyTTS")
    pr.add_argument("book", help="файл книги .epub или .fb2 (.fb2.zip)")
    pr.add_argument("-o", "--out", help="рабочая папка (по умолчанию out/<имя книги>)")
    pr.add_argument("--output", help="путь итогового .mytts-book (по умолчанию в рабочей папке)")
    pr.add_argument("--scope", choices=["auto", "book", "section"], default="auto", help="роман, сборник или определить")
    pr.add_argument("--voices", default=os.path.join(HERE, "voices_silero_cis.json"), help="голоса модели с полом")
    add_llm_options(pr)
    pr.set_defaults(func=process)
    e = sub.add_parser("extract", help="EPUB → кандидаты, запросы к LLM, указатель абзацев")
    e.add_argument("book", help="файл книги .epub или .fb2 (.fb2.zip)")
    e.add_argument("-o", "--out", required=True)
    e.add_argument("--scope", choices=["auto", "book", "section"], default="auto",
                   help="book — роман, section — сборник рассказов, auto — определить")
    e.add_argument("--min-count", type=int, default=2, help="минимум упоминаний (кроме названных в ремарках)")
    e.add_argument("--max-candidates", type=int, default=200, help="на один запрос")
    e.add_argument("--show", type=int, default=12)
    e.add_argument("--show-requests", type=int, default=30)
    e.set_defaults(func=extract)
    q = sub.add_parser("llm", help="отправить запросы в LLM (Ollama Cloud или DeepSeek)")
    q.add_argument("dir")
    add_llm_options(q)
    q.set_defaults(func=llm)
    a = sub.add_parser("apply", help="ответы LLM → cast.json")
    a.add_argument("dir")
    a.add_argument("--show", type=int, default=25)
    a.add_argument("--show-casts", type=int, default=30)
    a.set_defaults(func=apply)
    v = sub.add_parser("voices", help="раздать голоса персонажам cast.json")
    v.add_argument("dir")
    v.add_argument("--voices", default=os.path.join(HERE, "voices_silero_cis.json"))
    v.add_argument("--min-speaker", type=int, default=2, help="свой голос: не меньше реплик в ремарках…")
    v.add_argument("--min-mentions", type=int, default=30, help="…или не меньше упоминаний (главный герой без ремарок)")
    v.add_argument("--max-shared", type=int, default=2, help="общий голос: не больше общих абзацев")
    v.add_argument("--show", type=int, default=40)
    v.add_argument("--show-casts", type=int, default=6)
    v.set_defaults(func=voices)
    x = sub.add_parser("export", help="cast.json + отпечатки → файл .mytts-book для MyTTS и обмена")
    x.add_argument("dir")
    x.add_argument("-o", "--output")
    x.add_argument("--max-forms", type=int, default=24)
    x.add_argument("--keep-other", action="store_true", help="оставить персонажей с ролью «прочие» в списке")
    x.set_defaults(func=export)
    t = sub.add_parser("vectors", help="тест-векторы отпечатков для приложения")
    t.add_argument("-o", "--output", default="fingerprint_vectors.json")
    t.set_defaults(func=vectors)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
