#!/usr/bin/env python3
"""Собирает готовую базу звуков для MyTTS: папка pack/ с клипами, manifest.json и sounds.db.

  python3 tools/sound_bank/pack.py <каталог с <вид>.m4a> [pack]

Что получается:
  pack/<вид>.m4a        — 8 с, AAC 96 кбит/с, −14 LUFS (тот же формат, что приложение делает само);
  pack/manifest.json    — версия, модель, список звуков с sha256, размером и промптом;
  pack/sounds.db        — SQLite: таблица sounds и полнотекстовый индекс по промпту, чтобы быстро найти
                          готовый клип по виду (kind) или по смыслу описания. Приложение качает pack.zip
                          и берёт клип по виду, не тратя генерацию.
"""
import hashlib
import json
import os
import pathlib
import sqlite3
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
SOUNDS = REPO / "tools" / "sound_bank" / "sounds.json"
VERSION = "1"
# Где лежат файлы: GitHub допускает до 1000 вложений на релиз, поэтому варианты разложены по двум релизам.
RELEASES = {
    "": "sound-bank-v1",
    "_2": "sound-bank-v1",
    "_3": "sound-bank-v1-more",
}
BASE = "https://github.com/davnozdu/supertonic-android/releases/download"


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    src = pathlib.Path(sys.argv[1])
    out = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else REPO / "tools" / "sound_bank" / "pack"
    out.mkdir(parents=True, exist_ok=True)
    spec = json.loads(SOUNDS.read_text())
    made, missing = [], []
    for s in spec["sounds"]:
        key = s["key"]
        variants = []
        for suffix in ("", "_2", "_3"):
            clip = src / f"{key}{suffix}.m4a"
            if not clip.is_file() or clip.stat().st_size == 0:
                if suffix == "":
                    break
                continue
            dst = out / clip.name
            if not dst.is_file() or dst.stat().st_size != clip.stat().st_size:
                dst.write_bytes(clip.read_bytes())
            release = RELEASES.get(suffix, "sound-bank-v1")
            variants.append({
                "file": clip.name,
                "bytes": dst.stat().st_size,
                "sha256": sha256(dst),
                "url": f"{BASE}/{release}/{clip.name}",
            })
        if not variants:
            missing.append(key)
            continue
        base = variants[0]
        made.append({
            "key": key,
            "hint": s.get("hint") or "",
            "prompt": s["prompt"],
            "file": base["file"],
            "seconds": s.get("seconds", 8),
            "seed": s.get("seed", 0),
            "bytes": base["bytes"],
            "sha256": base["sha256"],
            "variants": variants,
        })
    manifest = {
        "version": VERSION,
        "model": spec.get("model"),
        "steps": spec.get("steps"),
        "decoder": spec.get("decoder"),
        "format": "m4a/aac 96 kbps 44100 Hz stereo, -14 LUFS, 8 s",
        "sounds": made,
    }
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1))

    db = out / "sounds.db"
    if db.exists():
        db.unlink()
    con = sqlite3.connect(db)
    con.executescript(
        """
        CREATE TABLE sounds (
            key TEXT PRIMARY KEY, hint TEXT, prompt TEXT, file TEXT,
            seconds REAL, seed INTEGER, bytes INTEGER, sha256 TEXT
        );
        CREATE INDEX sounds_file ON sounds(file);
        """
    )
    con.executemany(
        "INSERT INTO sounds VALUES (?,?,?,?,?,?,?,?)",
        [(s["key"], s["hint"], s["prompt"], s["file"], s["seconds"], s["seed"], s["bytes"], s["sha256"]) for s in made],
    )
    try:  # полнотекстовый поиск по описанию, если SQLite собран с FTS5
        con.executescript(
            """
            CREATE VIRTUAL TABLE sounds_fts USING fts5(key, hint, prompt, content='sounds', content_rowid='rowid');
            INSERT INTO sounds_fts(rowid, key, hint, prompt) SELECT rowid, key, hint, prompt FROM sounds;
            """
        )
    except sqlite3.OperationalError:
        pass
    con.commit()
    con.close()

    total = sum(s["bytes"] for s in made)
    print(f"pack: {len(made)} клипов, {total / 1e6:.1f} МБ, нет {len(missing)}")
    if missing:
        print("не сгенерированы:", ", ".join(missing[:12]), "…" if len(missing) > 12 else "")
    print("папка:", out)
    return 0 if made else 1


if __name__ == "__main__":
    sys.exit(main())
