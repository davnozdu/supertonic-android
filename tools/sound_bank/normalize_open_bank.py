#!/usr/bin/env python3
"""Project-matched, resumable loudness/dynamics preparation (no downloads).

Events: reference median rounded to -22 LUFS; scenes: project's -26 LUFS.
True-peak ceiling -3 dBTP. Soft 1.8:1 compression only for problematic dynamics.
Short impulses: preserve their attack and align peaks; R128 is not meaningful
below 400 ms. High-crest outliers go to review instead of being hard limited.
"""
import argparse
import concurrent.futures as futures
from datetime import datetime, timezone
import fcntl
import json
import math
import os
from pathlib import Path
import signal
import re
import subprocess
import sys
import time

import numpy as np
import prepare_bigsoundbank as bank

VERSION = 'project-ref-events22-scenes26-peak3-v2'
CEILING = -3.0
EVENT_TARGET = -22.0
SCENE_TARGET = -26.0
COMPRESSOR = 'acompressor=threshold=0.12589254:ratio=1.8:attack=10:release=180:knee=2.828427:makeup=1'


def finite(value):
    try:
        return math.isfinite(float(value))
    except (TypeError, ValueError):
        return False


def measure(path, filters=''):
    # Dedicated BS.1770 meter: no dynamic normalization or 192 kHz output.
    chain = (filters + ',' if filters else '') + 'ebur128=peak=true:framelog=verbose'
    p = subprocess.run(['ffmpeg', '-v', 'info', '-nostats', '-nostdin', '-threads', '1', '-i', str(path),
                        '-vn', '-af', chain, '-threads', '1', '-f', 'null', '-'],
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    if p.returncode:
        raise RuntimeError(p.stderr.decode(errors='replace')[-2000:])
    text = p.stderr.decode(errors='replace').rsplit('Summary:', 1)[-1]
    def number(pattern):
        m = re.search(pattern, text)
        if not m:
            raise ValueError('Missing loudness meter field: ' + pattern)
        return float(m.group(1)) if finite(m.group(1)) else None
    peak = number(r'Peak:\s+([-+a-zA-Z0-9.]+) dBFS')
    level = number(r'\bI:\s+([-+a-zA-Z0-9.]+) LUFS')
    return dict(input_i=None if level is not None and level <= -69.9 else level,
                input_tp=peak, input_lra=number(r'LRA:\s+([-+a-zA-Z0-9.]+) LU'),
                input_thresh=number(r'Threshold:\s+([-+a-zA-Z0-9.]+) LUFS'))


def active_level(path):
    pcm = bank.command(['ffmpeg', '-v', 'error', '-threads', '1', '-i', str(path),
                        '-ac', '1', '-ar', '8000', '-f', 'f32le', '-'])
    x = np.frombuffer(pcm, dtype='<f4')
    if not len(x) or not np.isfinite(x).all():
        raise ValueError('Invalid decoded audio')
    peak = float(np.max(np.abs(x)))
    if peak < 1e-6:
        return None
    active = x[np.abs(x) >= peak * .05]
    return 20 * math.log10(max(float(np.sqrt(np.mean(active * active))), 1e-12))


def recover(root):
    for pending in (root / 'normalization').glob('*.pending.json'):
        record = json.loads(pending.read_text())
        file = root / record['file']
        if file.exists() and file.stat().st_size == record['bytes'] and bank.digest(file) == record['sha256']:
            bank.atomic_json(root / 'state' / (record['id'] + '.json'), record)
            bank.atomic_json(file.with_suffix('.json'), record)
            previous = record.get('normalization', {}).get('previous_file')
            if previous and previous != record['file']:
                (root / previous).unlink(missing_ok=True)
                (root / previous).with_suffix('.json').unlink(missing_ok=True)
            pending.unlink()


def encode(path, temp, filters, row):
    bank.command(['ffmpeg', '-v', 'error', '-nostdin', '-y', '-threads', '1', '-i', str(path),
                  '-vn', '-af', filters, '-ar', '44100', '-ac', '2', '-c:a', 'aac', '-b:a', '128k',
                  '-threads', '1', '-map_metadata', '-1', '-movflags', '+faststart',
                  '-metadata', 'title=' + row['title'], '-metadata', 'artist=' + row.get('author_display', ''),
                  '-metadata', 'comment=CC0-1.0; ' + row['source_url'], str(temp)])


def normalize(row, root):
    path = root / row['file']
    if row.get('normalization', {}).get('version') == VERSION:
        if bank.digest(path) == row['sha256']:
            return row
        raise ValueError('Previously normalized file changed unexpectedly')
    original_hash = bank.digest(path)
    if original_hash != row['sha256']:
        raise ValueError('Input hash mismatch')
    before = measure(path)
    input_i, input_tp = before['input_i'], before['input_tp']
    duration = row['seconds']
    short = not finite(input_i) or duration < .4
    target = SCENE_TARGET if row['bucket'] == 'scenes' else EVENT_TARGET
    row = dict(row)
    if not finite(input_tp):
        row['normalization'] = dict(version=VERSION, method='silence: unchanged', before=before,
                                    after=before, needs_review=True, previous_file=row['file'])
        row.update(status='excluded', bucket='excluded', file='', bytes=0)
        bank.atomic_json(root / 'state' / (row['id'] + '.json'), row)
        path.unlink(missing_ok=True)
        path.with_suffix('.json').unlink(missing_ok=True)
        return row
    compressed_before = bool(row.get('normalization'))
    base = ''
    measured = before
    compression = False
    if not short and not compressed_before:
        desired_gain = target - input_i
        # Only compress where otherwise a high crest prevents useful leveling,
        # or where the original recording itself has a large dynamic range.
        if input_tp + desired_gain > CEILING + 2 or (before.get('input_lra') or 0) > 12:
            pregain = max(-40, min(40, desired_gain))
            base = f'volume={pregain:.6f}dB,' + COMPRESSOR
            measured = measure(path, base)
            compression = True
    if short:
        gain = CEILING - .5 - input_tp
    else:
        gain = min(target - measured['input_i'], CEILING - .5 - measured['input_tp'])
    gain = max(-50.0, min(50.0, gain))
    filters = (base + ',' if base else '') + f'volume={gain:.6f}dB'
    temp = path.with_suffix('.normalize.part.m4a')
    try:
        after = None
        for _ in range(3):
            encode(path, temp, filters, row)
            after = measure(temp)
            if not finite(after['input_tp']) or after['input_tp'] <= CEILING + .15:
                break
            gain -= after['input_tp'] - CEILING + .35
            filters = (base + ',' if base else '') + f'volume={gain:.6f}dB'
        if finite(after['input_tp']) and after['input_tp'] > CEILING + .2:
            raise ValueError('True peak still above project ceiling')
        info = bank.probe(temp)
        if abs(info['seconds'] - duration) > .12:
            raise ValueError('Normalization changed duration')
        gap = target - after['input_i'] if finite(after['input_i']) and not short else None
        needs_review = gap is not None and abs(gap) > 3
        previous_file = row['file']
        if needs_review:
            row.update(status='excluded', bucket='excluded', file='', bytes=0,
                       exclusion_reason='high crest requires excessive limiting')
            row['normalization'] = dict(version=VERSION, before=before, after=after, needs_review=True)
            bank.atomic_json(root / 'state' / (row['id'] + '.json'), row)
            path.unlink(missing_ok=True)
            path.with_suffix('.json').unlink(missing_ok=True)
            return row
        if needs_review and row['bucket'] != 'review':
            row['bucket_before_loudness_review'] = row['bucket']
            row['bucket'] = 'review'
            row['classification_reason'] += '; high crest: target requires excessive limiting'
        target_path = root / row['bucket'] / path.name
        bank.command(['ffmpeg', '-v', 'error', '-xerror', '-threads', '1', '-i', str(temp), '-f', 'null', '-'])
        row['normalization'] = dict(version=VERSION,
            method='short impulse: project peak alignment' if short else 'BS.1770 linear gain + optional soft compression',
            target_lufs=None if short else target, peak_ceiling_dbtp=CEILING,
            before=before, after=after, filters=filters, compressor_applied=compression,
            already_compressed=compressed_before, input_sha256=original_hash,
            target_gap_lu=gap, needs_review=needs_review, previous_file=previous_file,
            normalized_at=datetime.now(timezone.utc).isoformat())
        row['changes'] = row.get('changes', []) + [dict(operation='project_matched_normalization', version=VERSION)]
        row.update(bytes=temp.stat().st_size, sha256=bank.digest(temp), seconds=info['seconds'],
                   file=str(target_path.relative_to(root)))
        pending = root / 'normalization' / (row['id'] + '.pending.json')
        bank.atomic_json(pending, row)
        with temp.open('rb') as stream:
            os.fsync(stream.fileno())
        temp.replace(target_path)
        bank.atomic_json(root / 'state' / (row['id'] + '.json'), row)
        bank.atomic_json(target_path.with_suffix('.json'), row)
        if target_path != path:
            path.unlink(missing_ok=True)
            path.with_suffix('.json').unlink(missing_ok=True)
        pending.unlink()
        return row
    finally:
        temp.unlink(missing_ok=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out', type=Path, required=True)
    ap.add_argument('--workers', type=int, choices=range(1, 9), default=4)
    ap.add_argument('--limit', type=int, default=0)
    args = ap.parse_args(); root = args.out.resolve()
    lock = (root / 'run.lock').open('a+')
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise RuntimeError('Another preparation/normalization is running')
    (root / 'normalization').mkdir(exist_ok=True)
    (root / 'STOP').unlink(missing_ok=True)
    signal.signal(signal.SIGINT, lambda *_: bank.STOP.set())
    signal.signal(signal.SIGTERM, lambda *_: bank.STOP.set())
    recover(root)
    rows = [r for r in bank.records(root) if r['status'] == 'done']
    if args.limit: rows = rows[:args.limit]
    todo = [r for r in rows if r.get('normalization', {}).get('version') != VERSION]
    initial = len(rows) - len(todo); finished, started, errors = initial, time.monotonic(), []
    def progress(current):
        elapsed = time.monotonic() - started
        rate = (finished-initial)/elapsed if elapsed else 0
        left = len(rows)-finished
        state = dict(stage='normalization', total=len(rows), completed=finished, remaining=left,
                     already_completed=initial, workers=args.workers, elapsed_seconds=round(elapsed,1),
                     eta_seconds=round(left/rate) if rate else None, current=current, failures=errors,
                     stopped=bank.stopped(root), pid=os.getpid(), updated_at=datetime.now(timezone.utc).isoformat())
        bank.atomic_json(root/'progress.json', state)
        if finished % 25 == 0 or left == 0:
            print(f'Normalize {finished}/{len(rows)}, ETA {bank.eta(left/rate) if rate else "estimating"}; {current}', flush=True)
    progress('start')
    for begin in range(0, len(todo), 100):
        if bank.stopped(root): break
        iterator = iter(todo[begin:begin+100])
        with futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
            active = {}
            for _ in range(args.workers):
                item = next(iterator, None)
                if item is not None: active[pool.submit(normalize, item, root)] = item
            while active:
                done, _ = futures.wait(active, timeout=2, return_when=futures.FIRST_COMPLETED)
                for f in done:
                    item = active.pop(f)
                    try:
                        f.result(); finished += 1; progress(item['title'])
                    except Exception as exc:
                        errors.append(dict(id=item['id'], error=str(exc)))
                        bank.atomic_json(root/'normalization/errors.json', errors)
                        print('ERROR ' + json.dumps(errors[-1]), flush=True)
                    if not bank.stopped(root):
                        item = next(iterator, None)
                        if item is not None: active[pool.submit(normalize, item, root)] = item
        bank.indexes(root)
    progress('stopped' if bank.stopped(root) else 'finished')
    bank.atomic_json(root/'normalization/errors.json', errors)
    return 1 if errors else 0


if __name__ == '__main__':
    try: sys.exit(main())
    except KeyboardInterrupt: sys.exit(130)
    except Exception as exc:
        print('ERROR: ' + str(exc), file=sys.stderr); sys.exit(1)
