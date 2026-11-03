#!/usr/bin/env python3
"""База звуков событий: генерация на телефоне, сбор на компьютере, сборка и выкладка на GitHub.

Генерирует ТЕЛЕФОН (MyTTS, проба ambienceBankJob): средняя модель Stable Audio 3, 8 с, DiT на NPU. Он работает и
без компьютера: каждый готовый клип пишет в files/bank-gen/<имя>.wav и отмечает в done.txt; перезапуск пропускает
отмеченное. Компьютер, пока телефон подключён, забирает готовые клипы (и удаляет их с телефона), перезапускает
генерацию, если она встала, и ведёт счёт. Отключили телефон — он считает дальше; подключили — компьютер докачает.

Команды (рабочая папка ~/Downloads/sound-bank-v10, можно задать BANK_DIR):
  ab        6 одинаковых промптов для сравнения на слух (BANK_SECONDS=8 — вариант А, 30 — вариант Б)
  plan      задания: виды текущей базы (манифест с GitHub) × 3 варианта (тот же промпт, seed, seed+1, seed+2)
            + новые виды из new_kinds_v10.py × 3 разных промпта
  run       цикл: залить задания, запустить/перезапустить генерацию, забрать готовое; выходит, когда всё собрано
  status    сколько готово
  build     пик каждого клипа −3 dBFS (без ограничителя), m4a AAC 128 кбит/с 44,1 кГц стерео, manifest.json, 3 zip
  publish   релиз sound-bank-v10 (архивы + манифест); с --live ещё и manifest.json в sound-bank-v1 — приложение
            увидит новую версию и обновит базу само
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import wave
import zipfile

SERIAL = os.environ.get("ANDROID_SERIAL", "3B15AQ003F500000")
PKG = "com.davnozdu.supertonic.tts.fork"
SVC = f"{PKG}/com.brahmadeo.supertonic.tts.ambience.SoundGenService"
DEV = f"/data/data/{PKG}/files/bank-gen"
REPO = "davnozdu/supertonic-android"
VERSION = 10
RELEASE = f"sound-bank-v{VERSION}"
LIVE = "sound-bank-v1"          # здесь лежит manifest.json, который читает приложение
BASE = f"https://github.com/{REPO}/releases/download/{RELEASE}"
SECONDS = 8                     # длина клипа базы
GEN_SECONDS = int(os.environ.get("BANK_SECONDS", "8"))   # 8 — окно 30 с с условием «8 с»; 30 — генерация 30 с и вырезка 8 с
PEAK_DB = -3.0
ROOT = os.path.expanduser(os.environ.get("BANK_DIR", "~/Downloads/sound-bank-v10"))
RAW, M4A, LOG = (os.path.join(ROOT, d) for d in ("raw", "m4a", "log.txt"))
PLAN, JOBS = os.path.join(ROOT, "plan.json"), os.path.join(ROOT, "jobs.txt")
STALL_S = 6 * 60                # нет нового клипа столько времени при работающей генерации — перезапуск
HERE = os.path.dirname(os.path.abspath(__file__))


def log(msg: str):
    line = time.strftime("%H:%M:%S ") + msg
    print(line, flush=True)
    with open(LOG, "a") as f:
        f.write(line + "\n")


def adb(*args, timeout=120, binary=False):
    try:
        r = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return None
    if r.returncode != 0:
        return None
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def su(cmd: str, timeout=120):
    return adb("shell", "su -c " + "'" + cmd.replace("'", "'\\''") + "'", timeout=timeout)


def connected() -> bool:
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
    return any(l.startswith(SERIAL) and "\tdevice" in l for l in out.splitlines())


def variant_file(key: str, k: int) -> str:
    return f"{key}.m4a" if k == 1 else f"{key}_{k}.m4a"


def stable_seed(key: str) -> int:
    return int(hashlib.sha256(key.encode()).hexdigest()[:8], 16) % 100000


# ---------------------------------------------------------------- plan
def cmd_plan():
    os.makedirs(ROOT, exist_ok=True)
    live = json.loads(urllib.request.urlopen(f"https://github.com/{REPO}/releases/download/{LIVE}/manifest.json").read())
    sys.path.insert(0, HERE)
    from new_kinds_v10 import NEW as NEW_A
    from new_kinds_v10b import NEW as NEW_B
    NEW = NEW_A + NEW_B
    kinds, seen = [], set()
    for s in live["sounds"]:
        seed = int(s.get("seed") or stable_seed(s["key"]))
        kinds.append({"key": s["key"], "hint": s.get("hint", ""), "prompts": [s["prompt"]] * 3, "seeds": [seed, seed + 1, seed + 2]})
        seen.add(s["key"])
    added = 0
    for key, hint, prompts in NEW:
        if key in seen:
            continue
        seed = stable_seed(key)
        kinds.append({"key": key, "hint": hint, "prompts": list(prompts), "seeds": [seed, seed + 1, seed + 2]})
        seen.add(key); added += 1
    for k in kinds:
        for p in k["prompts"]:
            assert "|" not in p and "\n" not in p, k["key"]
    json.dump({"version": VERSION, "kinds": kinds}, open(PLAN, "w"), ensure_ascii=False, indent=1)
    with open(JOBS, "w") as f:
        for k in kinds:
            for i in range(3):
                f.write(f"{k['key']}_{i + 1}|{k['seeds'][i]}|{k['prompts'][i]}\n")
    log(f"план: видов {len(kinds)} (новых {added}), клипов {3 * len(kinds)} → {JOBS}")


def jobs():
    return [l.split("|", 2)[0] for l in open(JOBS).read().splitlines() if l.strip()]


def have() -> set:
    return {f[:-4] for f in os.listdir(RAW) if f.endswith(".wav")} if os.path.isdir(RAW) else set()


# ---------------------------------------------------------------- run
def service_running() -> bool:
    out = adb("shell", f"dumpsys activity services {SVC}") or ""
    return "ServiceRecord" in out


def phone_done() -> int:
    """Сколько клипов ТЕКУЩЕГО списка заданий телефон уже сделал (done.txt хранит и прошлые, например сравнение)."""
    out = su(f"cat {DEV}/done.txt 2>/dev/null") or ""
    return len({l.strip() for l in out.splitlines()} & set(jobs()))


def push_jobs():
    local = hashlib.sha256(open(JOBS, "rb").read()).hexdigest()
    remote = (su(f"sha256sum {DEV}/jobs.txt 2>/dev/null") or "").split(" ")[0].strip()
    if remote == local:
        return
    adb("push", JOBS, "/data/local/tmp/bank-jobs.txt")
    su(f"mkdir -p {DEV} && cp /data/local/tmp/bank-jobs.txt {DEV}/jobs.txt && "
       f"chown -R $(stat -c %u:%g /data/data/{PKG}) {DEV} && rm /data/local/tmp/bank-jobs.txt")
    log("задания залиты на телефон")


def start_generation(reason: str):
    su(f"am force-stop {PKG}")
    time.sleep(2)
    su(f"am start-foreground-service -n {SVC} --ez ambienceBankJob true --es ambienceModel medium "
       f"--ei ambienceSeconds {GEN_SECONDS} --ei ambienceThreads 4")
    log(f"генерация запущена ({reason})")


def pull_ready(want: set) -> int:
    listing = su(f"ls {DEV} 2>/dev/null") or ""
    names = [n.strip()[:-4] for n in listing.splitlines() if n.strip().endswith(".wav") and not n.startswith(".")]
    got = 0
    os.makedirs(RAW, exist_ok=True)
    for name in names:
        if name not in want:
            continue
        data = adb("exec-out", f"su -c 'cat {DEV}/{name}.wav'", binary=True, timeout=60)
        if not data or len(data) < 100_000:
            continue
        tmp = os.path.join(RAW, f".{name}.tmp")
        with open(tmp, "wb") as f:
            f.write(data)
        try:
            with wave.open(tmp) as w:
                dur = w.getnframes() / w.getframerate()
                ok = w.getnchannels() == 2 and w.getframerate() == 44100 and abs(dur - GEN_SECONDS) < 0.2
        except Exception:
            ok = False
        if not ok:
            os.remove(tmp); log(f"битый клип {name}, оставлен на телефоне"); continue
        if GEN_SECONDS > SECONDS:
            os.makedirs(os.path.join(ROOT, "raw30"), exist_ok=True)
            full = os.path.join(ROOT, "raw30", name + ".wav"); os.replace(tmp, full)
            trim_event(full, os.path.join(RAW, name + ".wav"))
        else:
            os.replace(tmp, os.path.join(RAW, name + ".wav"))
        su(f"rm -f {DEV}/{name}.wav")
        got += 1
    return got


def trim_event(src: str, dst: str, preroll: float = 0.3):
    """Из 30-секундной генерации — 8 с вокруг самого громкого места (начало за [preroll] до пика), как у прежней базы."""
    import array
    with wave.open(src) as w:
        sr, ch = w.getframerate(), w.getnchannels(); a = array.array("h"); a.frombytes(w.readframes(w.getnframes()))
    frames = len(a) // ch
    peak_i = max(range(0, len(a), ch), key=lambda i: abs(a[i]))
    start = max(0, int((peak_i / ch / sr - preroll) * sr)); n = SECONDS * sr
    start = min(start, max(0, frames - n))
    tmp = dst + ".tmp"
    with wave.open(tmp, "wb") as w:
        w.setnchannels(ch); w.setsampwidth(2); w.setframerate(sr); w.writeframes(a[start * ch:(start + n) * ch].tobytes())
    os.replace(tmp, dst)


def cmd_ab():
    """Сравнение на слух перед полной базой: одни и те же промпты, A — окно 30 с с условием 8 с, Б — 30 с с вырезкой."""
    prompts = [("door_slam", "a heavy wooden door slammed shut in a stone hallway"),
               ("footsteps_gravel", "footsteps crunching on loose gravel at a steady walking pace"),
               ("glass_break", "a drinking glass falling on a tiled floor and shattering"),
               ("dog_bark", "a large dog barking twice outside in a yard"),
               ("thunder_close", "a sharp close thunderclap followed by a long rolling rumble"),
               ("paper_crumple", "a sheet of paper crumpled into a ball by hand")]
    tag = "A8" if GEN_SECONDS == 8 else "B30"
    with open(JOBS, "w") as f:
        for key, p in prompts:
            f.write(f"ab_{tag}_{key}|{stable_seed(key)}|{p}\n")
    log(f"сравнение {tag}: {len(prompts)} клипов → {JOBS}; дальше: run")


SPEED = os.path.join(ROOT, "speed.json")


def speed_report(done: int, total: int) -> str:
    """Скорость по счётчику телефона (он считает и без компьютера) за последние 10 минут: с/клип, клипов/мин,
    сколько осталось и примерное время окончания. История — в speed.json, переживает перезапуск скрипта."""
    hist = json.load(open(SPEED)) if os.path.isfile(SPEED) else []
    now = time.time()
    if not hist or hist[-1][1] != done:
        hist.append([now, done])
    hist = [h for h in hist if now - h[0] < 3600][-500:]
    json.dump(hist, open(SPEED, "w"))
    window = [h for h in hist if now - h[0] <= 600] or hist
    if len(window) < 2 or window[-1][1] <= window[0][1]:
        return f"готово {done}/{total}; скорость ещё меряется"
    rate = (window[-1][1] - window[0][1]) / (window[-1][0] - window[0][0])   # клипов в секунду
    left = (total - done) / rate
    finish = time.strftime("%H:%M", time.localtime(now + left))
    return (f"готово {done}/{total} ({100 * done / total:.1f} %), {1 / rate:.1f} с/клип, {rate * 60:.1f} клипов/мин, "
            f"осталось ≈{left / 3600:.0f} ч {left % 3600 / 60:.0f} мин, конец ≈{finish}")


def memory() -> str:
    """Память телефона: свободная (MemAvailable), буферы NPU (IonTotalUsed), пул ядра DMA-BUF и рабочий :soundgen."""
    out = su("grep -E 'MemAvailable|IonTotalUsed' /proc/meminfo; cat /sys/kernel/dma_heap/total_pools_kb; "
             f"for p in $(pidof {PKG}:soundgen); do grep VmRSS /proc/$p/status; done") or ""
    vals = {}
    for line in out.splitlines():
        parts = line.split()
        if line.startswith("MemAvailable"): vals["avail"] = int(parts[1]) // 1024
        elif line.startswith("IonTotalUsed"): vals["npu"] = int(parts[1]) // 1024
        elif line.startswith("VmRSS"): vals["rss"] = int(parts[1]) // 1024
        elif parts and parts[0].isdigit(): vals["pool"] = int(parts[0]) // 1024
    s = (f"память: свободно {vals.get('avail', '?')} МБ, буферы NPU {vals.get('npu', '?')} МБ, пул ядра {vals.get('pool', '?')} МБ, "
         f":soundgen {vals.get('rss', '—')} МБ")
    if vals.get("avail", 99999) < 1000:
        s += "  ⚠ МАЛО СВОБОДНОЙ ПАМЯТИ"
    return s


def cmd_run():
    all_jobs = jobs()
    total = len(all_jobs)
    last_progress = time.time(); last_done = -1; was = None; last_report = 0.0
    while True:
        local = have()
        want = set(all_jobs) - local
        if not want:
            # Часть клипов сделал компьютер (mac_gen.py): телефон остановить, иначе он пересчитывал бы их.
            if connected():
                su(f"am force-stop {PKG}"); su(f"rm -f {DEV}/*.wav")
            log(f"всё собрано: {total} клипов"); return
        if not connected():
            if was is not False:
                log(f"телефон отключён — он считает сам; собрано {len(local)}/{total}, ждём подключения")
            was = False; time.sleep(10); continue
        if was is False:
            log("телефон подключён — забираем накопленное")
        was = True
        push_jobs()
        got = pull_ready(want)
        done = phone_done()
        running = service_running()
        if done != last_done:
            last_done, last_progress = done, time.time()
        status = (su(f"cat {DEV}/status.txt 2>/dev/null") or "").strip()
        if not running and done < total:
            err = (su(f"cat {DEV}/error.txt 2>/dev/null") or "").strip()
            if err:
                log("генерация упала с ошибкой:\n" + err[:1500]); return
            quick = getattr(cmd_run, "quick", 0)
            if time.time() - getattr(cmd_run, "started_at", 0) < 90:
                cmd_run.quick = quick + 1
                if cmd_run.quick >= 3:
                    log("генерация трижды завершилась сразу после запуска — остановка, см. status.txt на телефоне"); return
            start_generation("не запущена"); cmd_run.started_at = time.time()
            last_progress = time.time()
        elif running and time.time() - last_progress > STALL_S and not status.startswith("cooling"):
            start_generation(f"нет новых клипов {STALL_S // 60} мин")
            last_progress = time.time()
        if got or time.time() - last_report > 60:
            last_report = time.time()
            temp = re.search(r"temp=([\d.]+)", status)
            log(f"{speed_report(done, total)}; на компьютере {len(have())}; {memory()}"
                + (f"; батарея {temp.group(1)} °C" if temp else "") + ("; ОСТЫВАЕТ" if status.startswith("cooling") else ""))
        time.sleep(8)


def cmd_status():
    total = len(jobs())
    print(f"собрано на компьютере: {len(have())}/{total}")
    if connected():
        done = phone_done()
        print("телефон:", (su(f"cat {DEV}/status.txt 2>/dev/null") or "").strip(),
              "| генерация", "идёт" if service_running() else "не запущена")
        print(speed_report(done, total))
        print(memory())


# ---------------------------------------------------------------- build
def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def peak_db(path: str) -> float:
    import array
    with wave.open(path) as w:
        a = array.array("h"); a.frombytes(w.readframes(w.getnframes()))
    m = max((abs(x) for x in a), default=0)
    import math
    return 20 * math.log10(max(m, 1) / 32768)


def cmd_build():
    plan = json.load(open(PLAN))
    os.makedirs(M4A, exist_ok=True)
    sounds, missing = [], []
    for k in plan["kinds"]:
        variants = []
        for i in (1, 2, 3):
            src = os.path.join(RAW, f"{k['key']}_{i}.wav")
            dst = os.path.join(M4A, variant_file(k["key"], i))
            if not os.path.isfile(src):
                missing.append(src); continue
            if not os.path.isfile(dst):
                gain = PEAK_DB - peak_db(src)   # только усиление по пику: ограничителя нет, хрипа нет
                tmp = dst + ".tmp.m4a"
                r = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", src, "-af", f"volume={gain:.2f}dB",
                                    "-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2", tmp])
                if r.returncode != 0:
                    log(f"ошибка кодирования {src}"); continue
                os.replace(tmp, dst)
            # Как в v9: без ссылок на отдельные файлы — приложение берёт клипы только из трёх архивов
            # (GitHub плохо переносит тысячи отдельных вложений).
            variants.append({"file": os.path.basename(dst), "bytes": os.path.getsize(dst), "sha256": sha256(dst),
                             "prompt": k["prompts"][i - 1]})
        if len(variants) != 3:
            continue
        sounds.append({"key": k["key"], "hint": k["hint"], "prompt": k["prompts"][0], "seed": k["seeds"][0],
                       "file": variants[0]["file"], "bytes": variants[0]["bytes"], "sha256": variants[0]["sha256"],
                       "seconds": SECONDS, "variants": variants})
    if missing:
        log(f"нет клипов: {len(missing)} (первые {missing[:3]}) — сначала run"); return
    files = sorted(f for f in os.listdir(M4A) if f.endswith(".m4a"))
    part = sum(os.path.getsize(os.path.join(M4A, f)) for f in files) // 3 + 1
    groups, acc = [[]], 0
    for f in files:
        size = os.path.getsize(os.path.join(M4A, f))
        if acc + size > part and len(groups) < 3:
            groups.append([]); acc = 0
        groups[-1].append(f); acc += size
    archive = {"bytes": {}, "sha256": {}}
    for name, group in zip(("base", "second", "more"), groups):
        path = os.path.join(ROOT, f"{RELEASE}-{name}.zip")
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
            for f in group:
                z.write(os.path.join(M4A, f), f)
        archive[name] = f"{BASE}/{RELEASE}-{name}.zip"
        archive["bytes"][name] = os.path.getsize(path); archive["sha256"][name] = sha256(path)
    manifest = {"bank": VERSION, "version": str(VERSION), "model": "stable-audio-3-medium", "steps": 8, "decoder": "same-s",
                "format": "m4a/aac 128 кбит/с, 44100 Гц, стерео; пик каждого клипа −3 dBFS, без ограничителя",
                "zip": archive, "sounds": sounds}
    json.dump(manifest, open(os.path.join(ROOT, "manifest.json"), "w"), ensure_ascii=False, indent=1)
    log(f"собрано: видов {len(sounds)}, файлов {len(files)}, архивы {[archive['bytes'][n] for n in ('base', 'second', 'more')]}")


def cmd_publish(live: bool):
    files = [os.path.join(ROOT, f"{RELEASE}-{n}.zip") for n in ("base", "second", "more")] + [os.path.join(ROOT, "manifest.json")]
    if subprocess.run(["gh", "release", "view", RELEASE, "-R", REPO], capture_output=True).returncode != 0:
        subprocess.run(["gh", "release", "create", RELEASE, "-R", REPO, "--prerelease", "--title", f"База звуков v{VERSION}",
                        "--notes", "Звуки событий для MyTTS: средняя модель Stable Audio 3, 8 с, пик −3 dBFS."], check=True)
    subprocess.run(["gh", "release", "upload", RELEASE, *files, "-R", REPO, "--clobber"], check=True)
    for f in files:   # проверка: размер на GitHub = локальному
        name = os.path.basename(f)
        out = subprocess.run(["gh", "release", "view", RELEASE, "-R", REPO, "--json", "assets", "-q",
                              f'.assets[] | select(.name=="{name}") | .size'], capture_output=True, text=True).stdout.strip()
        assert out == str(os.path.getsize(f)), f"{name}: {out} != {os.path.getsize(f)}"
    log(f"выложено в {RELEASE}")
    if live:
        subprocess.run(["gh", "release", "upload", LIVE, os.path.join(ROOT, "manifest.json"), "-R", REPO, "--clobber"], check=True)
        log(f"manifest.json заменён в {LIVE}: приложения обновят базу до v{VERSION}")


if __name__ == "__main__":
    os.makedirs(ROOT, exist_ok=True)
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    {"plan": cmd_plan, "ab": cmd_ab, "run": cmd_run, "status": cmd_status, "build": cmd_build,
     "publish": lambda: cmd_publish("--live" in sys.argv)}[cmd]()
