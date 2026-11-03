#!/usr/bin/env python3
"""Вторая «машина» для базы звуков: компьютер генерирует те же задания, что телефон, но с КОНЦА списка
(телефон идёт с начала). Готовое кладёт туда же, куда bank_gen.py забирает клипы телефона (BANK_DIR/raw и raw30),
поэтому сборщик считает их сделанными. Встретились — работа закончена.

Модель — та же средняя Stable Audio 3 (dit_medium_w8a8.tflite на процессоре), T5 tflite, 8 шагов pingpong, тот же
seed и промпт; декодер SAME-L fp32 (эталонная модель Stability, полосное внимание — тот же результат, что плотное).
Генерация 30 с, вырезка 8 с от 0,3 с до пика — как вариант Б на телефоне.

Нужны файлы (MODELS): encoder_fp16.tflite, tokenizer.model, dit_medium_w8a8.tflite и samel/ (banded.py,
same_l_decoder_torch.py, same_l_decoder_f32.npz). Запуск:
  BANK_DIR=~/Downloads/sound-bank-v10 MODELS=<папка> python3 mac_gen.py
"""
import math, os, sys, time, wave
import numpy as np

ROOT = os.path.expanduser(os.environ.get("BANK_DIR", "~/Downloads/sound-bank-v10"))
MODELS = os.path.expanduser(os.environ["MODELS"])
sys.path.insert(0, os.path.join(MODELS, "samel"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import torch, sentencepiece as spm
from ai_edge_litert.interpreter import Interpreter
import banded
os.environ.setdefault("BANK_SECONDS", "30")
import bank_gen   # общие trim_event, SECONDS

CH, MEM, STEPS, SECONDS = 256, 64, 8, 30.0
RUNGS = [8, 48, 96, 192, 416, 704, 1056, 1496, 2040, 2824, 3536, 4096]
L = math.ceil(SECONDS * 44100 / 4096)


def sched():
    return [1.0 if i == 0 else (0.0 if 1 - i / STEPS <= 0 else 1 / (1 + math.exp(2.0 - (1 - i / STEPS) * 8.2))) for i in range(STEPS + 1)]


def log(msg):
    line = time.strftime("%H:%M:%S ") + "[компьютер] " + msg
    print(line, flush=True)
    with open(os.path.join(ROOT, "log.txt"), "a") as f:
        f.write(line + "\n")


def main():
    torch.set_num_threads(int(os.environ.get("THREADS", "6")))
    sp = spm.SentencePieceProcessor(model_file=os.path.join(MODELS, "tokenizer.model"))
    enc = Interpreter(model_path=os.path.join(MODELS, "encoder_fp16.tflite"), num_threads=8).get_signature_runner("serving_default")
    dit = Interpreter(model_path=os.path.join(MODELS, "dit_medium_w8a8.tflite"), num_threads=8)
    rung = next(r for r in RUNGS if r >= L)
    D, G = dit.get_signature_runner(f"s{rung}"), dit.get_signature_runner("gcond")
    dec = banded.banded_model(os.path.join(MODELS, "samel", "same_l_decoder_f32.npz"), L + (L % 2)).eval()
    jobs = [l.split("|", 2) for l in open(os.path.join(ROOT, "jobs.txt")).read().splitlines() if l.strip()]
    raw, raw30 = os.path.join(ROOT, "raw"), os.path.join(ROOT, "raw30")
    os.makedirs(raw, exist_ok=True); os.makedirs(raw30, exist_ok=True)
    made, t_start = 0, time.time()
    for name, seed, prompt in reversed(jobs):
        if os.path.isfile(os.path.join(raw, name + ".wav")):
            # Дошли до клипа, который уже есть (сделан телефоном или нами раньше). Идём дальше: телефон мог
            # пропустить отдельные клипы; но если подряд 30 готовых — значит, встретились с телефоном.
            made_streak = getattr(main, "streak", 0) + 1; main.streak = made_streak
            if made_streak >= 30:
                log(f"встретились с телефоном — готово, сделано на компьютере {made}"); return
            continue
        main.streak = 0
        t0 = time.time()
        toks = sp.encode(prompt)[:256]
        ids = np.zeros((1, 256), np.int32); mk = np.zeros((1, 256), np.int32)
        ids[0, :len(toks)] = toks; mk[0, :len(toks)] = 1
        h = enc(args_0=ids, args_1=mk)["output_0"]
        sig = sched(); rng = np.random.default_rng(int(seed))
        x = rng.standard_normal((CH, L)).astype(np.float32)
        lm = np.zeros(MEM + rung, np.float32); lm[MEM + L:] = -1e9
        for i in range(STEPS):
            tc, tn = sig[i], sig[i + 1]
            g = G(args_0=np.array([SECONDS], np.float32), args_1=np.array([tc], np.float32))["output_0"]
            pad = np.zeros((CH, rung), np.float32); pad[:, :L] = x
            v = D(args_0=pad.reshape(1, CH, rung), args_1=g, args_2=h.reshape(1, 256, 768), args_3=mk.astype(np.float32),
                  args_4=np.array([SECONDS], np.float32), args_5=np.zeros((1, 257, rung), np.float32),
                  args_6=lm.reshape(1, -1))["output_0"].reshape(CH, rung)[:, :L]
            d = x - tc * v
            x = ((1 - tn) * d + tn * rng.standard_normal(x.shape)).astype(np.float32) if i < STEPS - 1 and tn > 0 else d
        lat = np.concatenate([x, x[:, -1:].repeat(L % 2, 1)], 1)[None] if L % 2 else x[None]
        with torch.no_grad():
            y = dec(torch.from_numpy(lat.astype(np.float32)))[0].numpy()[:, :int(SECONDS * 44100)]
        full = os.path.join(raw30, name + ".wav"); tmp = full + ".tmp"
        with wave.open(tmp, "wb") as w:
            w.setnchannels(2); w.setsampwidth(2); w.setframerate(44100)
            w.writeframes((np.clip(y.T, -1, 1) * 32767).astype(np.int16).tobytes())
        os.replace(tmp, full)
        if os.path.isfile(os.path.join(raw, name + ".wav")):
            continue   # пока считали, телефон успел сделать этот же клип — его оставляем
        bank_gen.trim_event(full, os.path.join(raw, name + ".wav"))
        made += 1
        if made % 5 == 0 or made == 1:
            log(f"сделано на компьютере {made}, последний {name}, {time.time() - t0:.1f} с/клип, "
                f"в среднем {(time.time() - t_start) / made:.1f} с")


if __name__ == "__main__":
    main()
