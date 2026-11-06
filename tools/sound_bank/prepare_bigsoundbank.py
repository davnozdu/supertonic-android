#!/usr/bin/env python3
"""Resumable batch preparation: BigSoundBank → events/scenes/review.

  python3 tools/sound_bank/prepare_bigsoundbank.py run --source listening/bigsoundbank-100 --out listening/open-bank --limit 100
  python3 tools/sound_bank/prepare_bigsoundbank.py run --source listening/bigsoundbank-100 --out listening/open-bank
  python3 tools/sound_bank/prepare_bigsoundbank.py status --out listening/open-bank
  python3 tools/sound_bank/prepare_bigsoundbank.py stop --out listening/open-bank

Requires Python 3.9+, numpy, curl, ffmpeg and ffprobe. Original audio is removed
only after a complete, verified output and durable searchable indexes exist.
Run again after reboot to resume. Uses the inventory produced by download_bigsoundbank.py.
"""
import argparse
import concurrent.futures as futures
from datetime import datetime, timezone
import csv
import fcntl
import hashlib
import html
import json
import math
import os
from pathlib import Path
import re
import signal
import sqlite3
import subprocess
import sys
import threading
import time
import unicodedata

import numpy as np
import download_bigsoundbank as download

PIPELINE = 'bsb-spectral-v1-aac128'
STOP = threading.Event()
RU_TERMS = {
    'door': 'дверь двери открытие закрытие', 'telephone': 'телефон звонок',
    'phone': 'телефон звонок', 'car': 'автомобиль машина', 'forest': 'лес деревья',
    'urban': 'улица город', 'street': 'улица город', 'rain': 'дождь',
    'thunder': 'гром гроза', 'wind': 'ветер', 'water': 'вода', 'fire': 'огонь костёр',
    'bird': 'птицы', 'birdsong': 'птицы пение', 'footsteps': 'шаги ходьба',
    'cough': 'кашель', 'laugh': 'смех', 'cry': 'плач', 'crying': 'плач',
    'baby': 'младенец ребёнок', 'breath': 'дыхание', 'breathing': 'дыхание',
    'knock': 'стук', 'bell': 'колокол звонок', 'alarm': 'сигнал тревога',
    'cat': 'кошка кот', 'dog': 'собака', 'horse': 'лошадь', 'crowd': 'толпа люди',
    'crowds': 'толпа люди', 'sea': 'море', 'waves': 'волны', 'stream': 'ручей',
    'train': 'поезд', 'trains': 'поезд', 'clock': 'часы', 'clocks': 'часы',
    'glass': 'стекло', 'paper': 'бумага', 'applause': 'аплодисменты',
    'engine': 'двигатель мотор', 'machines': 'механизмы машины',
}



def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.tmp')
    with tmp.open('w') as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
        f.write('\n')
        f.flush()
        os.fsync(f.fileno())
    tmp.replace(path)


def digest(path):
    return download.sha256(path)


def command(args):
    p = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode:
        raise RuntimeError(p.stderr.decode(errors='replace')[-1500:])
    return p.stdout


def probe(path):
    x = json.loads(command(['ffprobe', '-v', 'error', '-select_streams', 'a:0',
                           '-show_entries', 'stream=codec_name,sample_rate,channels:format=duration',
                           '-of', 'json', str(path)]))
    stream = x['streams'][0]
    return dict(seconds=float(x['format']['duration']), codec=stream['codec_name'],
                sample_rate=int(stream['sample_rate']), channels=int(stream['channels']))


def enriched(item, source):
    item = dict(item)
    page = source / 'pages' / (item['id'] + '.html')
    if not page.is_file():
        raise ValueError('Source page missing: ' + str(page))
    raw = page.read_text()
    x = download.audio_object(raw)
    item['tags'] = [s.strip() for s in x.get('keywords', '').split(',') if s.strip()]
    a = raw.find('Technical details')
    b = raw.find('Other interesting', a)
    detail = html.unescape(re.sub('<[^>]*>', ' ', raw[a:b if b > a else a + 5000]))
    detail = re.sub(r'\s+', ' ', detail)
    for key, pattern in [
        ('category', r'UCS Category\s*:\s*(.*?)\s*Type\s*:'),
        ('source_type', r'Type\s*:\s*(.*?)\s*Channels\s*:'),
        ('author_display', r'Author\s*:\s*(.*?)\s*Sound number\s*:')]:
        match = re.search(pattern, detail)
        item[key] = match.group(1).strip() if match else ''
    # JSON-LD often names the site owner instead of the actual recording author.
    item['schema_author'] = item.get('author')
    if item['author_display']:
        item['author'] = item['author_display']
    words = set(re.findall(r'[a-z]+', (item['title'] + ' ' + item.get('description', '') + ' ' + item['category'] + ' ' + ' '.join(item['tags'])).lower()))
    item['search_aliases_ru'] = sorted({term for word, terms in RU_TERMS.items() if word in words for term in terms.split()})
    item['source_page_sha256'] = digest(page)
    return item


def classify(item):
    title = item['title'].lower()
    category = item['category'].split('/')[0].strip().lower()
    kind = item['source_type']
    if item.get('license') != 'CC0-1.0':
        return 'review', 'license is not explicitly CC0'
    if not item['author_display'] or re.search(r'anon[yi]m|unknown', item['author_display'], re.I):
        return 'review', 'author/provenance unclear'
    if category in ('musical', 'archived') or kind in ('Music', 'Sample', 'Voices (Text)'):
        return 'review', 'music, spoken text, sample or technical recording'
    if re.search(r'iphone|wilhelm|fah\b|sncf|windows|nokia|netflix|meme|silence|noise|test tone', title):
        return 'review', 'recognisable jingle/ringtone, meme, silence or test noise'
    if kind == 'Soundscape':
        if category in ('ambience', 'weather', 'wind', 'rain', 'water', 'fire', 'birds',
                        'animals', 'crowds', 'vehicles', 'trains', 'machines', 'motors',
                        'aircraft', 'boats', 'human', 'clocks'):
            return 'scenes', 'source labels a soundscape; environmental UCS category'
        return 'review', 'soundscape with an uncertain scene category'
    if kind in ('Alone sound', 'Foley') and category and category != 'ambience':
        return 'events', 'source labels an isolated sound or Foley'
    return 'review', 'missing or conflicting type/category'


def spectral_window(path, duration):
    """STFT onset/energy novelty → densest 8 s event, 0.3 s onset preroll.

    Not speech recognition. Keep the decision metrics so a clip can be reviewed.
    No gain, dynamics processing or sample stretching is applied.
    """
    if duration <= 8.0:
        return 0.0, duration, {'method': 'whole short event', 'review': False}
    data = command(['ffmpeg', '-v', 'error', '-threads', '1', '-i', str(path),
                    '-vn', '-ac', '1', '-ar', '8000', '-f', 'f32le', '-'])
    audio = np.frombuffer(data, dtype='<f4')
    if not len(audio) or not np.isfinite(audio).all():
        raise ValueError('Empty or non-finite source audio')
    frame, hop, sr = 256, 160, 8000
    if len(audio) < frame:
        return 0.0, min(duration, 8), {'method': 'short decode', 'review': True}
    frames = np.lib.stride_tricks.sliding_window_view(audio, frame)[::hop]
    # Work in chunks: STFT memory does not grow with a long recording's duration.
    energy, flux = [], []
    previous = None
    window = np.hanning(frame)
    for block in range(0, len(frames), 4096):
        f = frames[block:block + 4096]
        energy.extend(np.sqrt(np.mean(f * f, axis=1)).tolist())
        spectrum = np.log1p(100 * np.abs(np.fft.rfft(f * window, axis=1))[:, 2:113])
        before = np.vstack([spectrum[:1] if previous is None else previous[None, :], spectrum[:-1]])
        flux.extend(np.maximum(spectrum - before, 0).mean(axis=1).tolist())
        previous = spectrum[-1]
    rms, novelty = np.asarray(energy), np.asarray(flux)
    max_rms = float(rms.max())
    if max_rms < 1e-5:
        return 0.0, 8.0, {'method': 'STFT', 'review': True, 'reason': 'near silence'}
    noise = float(np.percentile(rms, 20))
    threshold = max(max_rms * .06, min(noise * 2.5, max_rms * .4), 1e-5)
    activity = np.clip((rms - threshold) / max(max_rms - threshold, 1e-9), 0, 1)
    novelty /= max(float(np.percentile(novelty, 95)), 1e-6)
    score = activity + .7 * np.clip(novelty, 0, 3) * (rms > threshold)
    width = min(len(score), round(8 * sr / hop))
    cumulative = np.r_[0, np.cumsum(score)]
    scores = cumulative[width:] - cumulative[:-width]
    best = int(np.argmax(scores))
    # Earliest strong onset within the winning window; retain 0.3 s before it.
    candidates = np.flatnonzero((novelty[best:best + width] > .5)
                                & (rms[best:best + width] > threshold))
    onset = best + int(candidates[0]) if len(candidates) else best
    start = max(0.0, min(onset * hop / sr - .3, duration - 8))
    return start, 8.0, dict(method='STFT 8kHz/256/160, positive spectral flux + RMS',
                            onset_seconds=round(onset * hop / sr, 3),
                            noise_rms=noise, max_rms=max_rms, activity_threshold=threshold,
                            review=False)


def filename(item):
    title = unicodedata.normalize('NFKD', item['title']).encode('ascii', 'ignore').decode().lower()
    title = re.sub(r'[^a-z0-9]+', '-', title).strip('-')[:95] or 'sound'
    return f"bsb-{item['id']}-{title}.m4a"


def complete(root, sid, verify=False):
    # Finish an interrupted normalized-file replacement before interpreting a
    # changed hash as a need to fetch the source again.
    pending = root / 'normalization' / (sid + '.pending.json')
    if pending.exists():
        try:
            recovered = json.loads(pending.read_text())
            file = root / recovered['file']
            if file.is_file() and file.stat().st_size == recovered['bytes'] and digest(file) == recovered['sha256']:
                atomic_json(root / 'state' / (sid + '.json'), recovered)
                atomic_json(file.with_suffix('.json'), recovered)
                previous = recovered.get('normalization', {}).get('previous_file')
                if previous and previous != recovered['file']:
                    (root / previous).unlink(missing_ok=True)
                    (root / previous).with_suffix('.json').unlink(missing_ok=True)
                pending.unlink()
        except (OSError, ValueError, KeyError):
            pass
    receipt = root / 'state' / (sid + '.json')
    if not receipt.exists():
        return None
    try:
        result = json.loads(receipt.read_text())
        if result['pipeline'] != PIPELINE:
            return None
        if result['status'] in ('metadata-only', 'excluded'):
            return result
        f = root / result['file']
        if f.stat().st_size != result['bytes']:
            return None
        if verify and digest(f) != result['sha256']:
            return None
        return result
    except (OSError, ValueError, KeyError):
        return None


def process(item, source, root):
    old = complete(root, item['id'], verify=True)
    if old:
        return old
    item = enriched(item, source)
    bucket, reason = classify(item)
    state = dict(item, pipeline=PIPELINE, bucket=bucket, classification_reason=reason,
                 finished_at=datetime.now(timezone.utc).isoformat())
    if bucket == 'review':
        state.update(status='excluded', bucket='excluded', file='', bytes=0,
                     reason='Kept for review; audio not downloaded without confirmed licence')
        atomic_json(root / 'state' / (item['id'] + '.json'), state)
        return state
    failures = []
    for fmt in ['wav', 'flac', 'mp3', 'm4a']:
        if fmt not in item['encodings']:
            continue
        original = source / 'audio' / (item['id'] + '.' + fmt)
        try:
            if not original.exists():
                download.download_sound(item, source, fmt)
            elif original.stat().st_size != item['encodings'][fmt]['bytes']:
                raise ValueError('Original size mismatch')
            break
        except RuntimeError as exc:
            if '404' not in str(exc):
                raise
            failures.append(dict(format=fmt, error=str(exc)))
    else:
        raise ValueError('All source formats unavailable: ' + json.dumps(failures))
    state['source_format_fallbacks'] = failures
    original_hash = digest(original)
    info = probe(original)
    start, duration, selection = 0.0, info['seconds'], {'method': 'full duration'}
    if bucket == 'events':
        start, duration, selection = spectral_window(original, info['seconds'])
        if selection.get('review'):
            bucket, reason = 'review', 'event extraction uncertain: ' + selection.get('reason', '')
            start, duration = 0.0, info['seconds']
    if bucket == 'review':
        state.update(status='excluded', bucket='excluded', file='', bytes=0, reason=reason)
        atomic_json(root / 'state' / (item['id'] + '.json'), state)
        return state
    target = root / bucket / filename(item)
    temp = target.with_suffix('.part.m4a')
    args = ['ffmpeg', '-v', 'error', '-nostdin', '-y', '-threads', '1', '-i', str(original)]
    if bucket == 'events':
        args += ['-ss', f'{start:.6f}', '-t', f'{duration:.6f}']
    args += ['-vn', '-map_metadata', '-1', '-c:a', 'aac', '-b:a', '128k', '-ar', '44100',
             '-ac', '2', '-threads', '1', '-movflags', '+faststart', '-metadata',
             'title=' + item['title'], '-metadata', 'artist=' + item['author_display'],
             '-metadata', 'comment=CC0-1.0; ' + item['source_url'], str(temp)]
    try:
        command(args)
        output = probe(temp)
        wanted = min(8.0, info['seconds']) if bucket == 'events' else info['seconds']
        if abs(output['seconds'] - wanted) > .12:
            raise ValueError(f'Duration mismatch: expected {wanted}, got {output["seconds"]}')
        if output['codec'] != 'aac' or output['sample_rate'] != 44100 or output['channels'] != 2:
            raise ValueError('Output format mismatch')
        # Decode the entire output to detect truncated/corrupt encodings before deleting originals.
        command(['ffmpeg', '-v', 'error', '-xerror', '-threads', '1', '-i', str(temp), '-f', 'null', '-'])
        target.parent.mkdir(parents=True, exist_ok=True)
        temp.replace(target)
        state.update(status='done', bucket=bucket, classification_reason=reason,
                     file=str(target.relative_to(root)), bytes=target.stat().st_size,
                     sha256=digest(target), original_bytes=original.stat().st_size,
                     original_sha256=original_hash, original_format=fmt,
                     original_seconds=info['seconds'], seconds=output['seconds'],
                     trim_start=start, selection=selection,
                     changes=[{'operation': 'trim', 'start': start, 'seconds': duration}] if bucket == 'events' and info['seconds'] > 8 else [])
        state['changes'].append({'operation': 'encode', 'codec': 'AAC', 'bitrate': 128000,
                                 'sample_rate': 44100, 'channels': 2, 'gain': 'unchanged'})
        atomic_json(target.with_suffix('.json'), state)
        atomic_json(root / 'state' / (item['id'] + '.json'), state)
        return state
    finally:
        temp.unlink(missing_ok=True)


def records(root):
    return [json.loads(p.read_text()) for p in sorted((root / 'state').glob('*.json'))]


def indexes(root):
    all_rows = records(root)
    rows = [r for r in all_rows if r['status'] == 'done']
    for bucket in ['events', 'scenes']:
        selected = [r for r in rows if r['bucket'] == bucket]
        atomic_json(root / bucket / 'manifest.json', {'schema': 1, 'bank': 'bigsoundbank-' + bucket,
                    'format': 'AAC 128k 44100Hz stereo', 'sounds': selected})
        csv_path = root / bucket / 'catalog.csv'
        with csv_path.with_suffix('.csv.tmp').open('w', encoding='utf-8-sig', newline='') as f:
            writer = csv.writer(f)
            writer.writerow(['id', 'file', 'title', 'description', 'tags', 'search_ru', 'seconds', 'MB', 'author', 'license', 'source_url', 'classification_reason'])
            for r in selected:
                writer.writerow([r['id'], r['file'], r['title'], r.get('description', ''),
                    ', '.join(r.get('tags', [])), ', '.join(r.get('search_aliases_ru', [])),
                    r.get('seconds', ''), round(r['bytes']/1e6, 3), r.get('author_display', ''),
                    r['license'], r['source_url'], r['classification_reason']])
        csv_path.with_suffix('.csv.tmp').replace(csv_path)
        db = root / bucket / 'sounds.db.tmp'
        db.unlink(missing_ok=True)
        con = sqlite3.connect(db)
        con.execute('CREATE TABLE sounds (id TEXT PRIMARY KEY, file TEXT, title TEXT, description TEXT, tags TEXT, category TEXT, seconds REAL, bytes INTEGER, sha256 TEXT, license TEXT, source_url TEXT)')
        con.executemany('INSERT INTO sounds VALUES (?,?,?,?,?,?,?,?,?,?,?)', [
            (r['id'], r['file'], r['title'], r.get('description', ''), ' '.join(r.get('tags', []) + r.get('search_aliases_ru', [])),
             r['category'], r.get('seconds', 0), r['bytes'], r.get('sha256', ''), r['license'], r['source_url'])
            for r in selected])
        con.execute("CREATE VIRTUAL TABLE sounds_fts USING fts4(id, title, description, tags, category, tokenize=unicode61)")
        con.execute('INSERT INTO sounds_fts SELECT id,title,description,tags,category FROM sounds')
        con.commit()
        if con.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise ValueError('Search database integrity failed')
        con.close()
        db.replace(root / bucket / 'sounds.db')
    (root / 'CREDITS.md').write_text('# Recording sources and licenses\n\n' + '\n\n'.join(
        f"- {r['file'] or r['id']}: {r['title']}\n  Author: {r.get('author_display', '')}\n"
        f"  Source: {r['source_url']}\n  License: {r['license']} {r.get('license_url', '')}"
        for r in rows) + '\n')
    summary = {}
    for bucket in ['events', 'scenes']:
        selected = [r for r in rows if r['bucket'] == bucket]
        summary[bucket] = dict(records=len(selected),
            audio_files=sum(r['status'] == 'done' for r in selected),
            metadata_only=sum(r['status'] == 'metadata-only' for r in selected),
            bytes=sum(r['bytes'] for r in selected),
            seconds=sum(r.get('seconds', 0) for r in selected))
    summary['excluded'] = dict(records=sum(r['status'] == 'excluded' for r in all_rows))
    atomic_json(root / 'bank-summary.json', summary)
    return rows


def stopped(root):
    return STOP.is_set() or (root / 'STOP').exists()


def eta(seconds):
    return f'{int(seconds)//3600:02}:{int(seconds)%3600//60:02}:{int(seconds)%60:02}'


def run(args):
    root, source = args.out.resolve(), args.source.resolve()
    root.mkdir(parents=True, exist_ok=True)
    lock = (root / 'run.lock').open('a+')
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise RuntimeError('Another preparation is already running in this output directory')
    for folder in ['events', 'scenes', 'state']:
        (root / folder).mkdir(exist_ok=True)
    (root / 'STOP').unlink(missing_ok=True)
    signal.signal(signal.SIGINT, lambda *_: STOP.set())
    signal.signal(signal.SIGTERM, lambda *_: STOP.set())
    catalog = json.loads((source / 'inventory.json').read_text())
    items = sorted(catalog['sounds'], key=lambda x: int(x['id']))
    if args.limit:
        items = items[:args.limit]
    valid, todo = [], []
    print('Checking existing output hashes...', flush=True)
    for item in items:
        if stopped(root):
            break
        old = complete(root, item['id'], verify=True)
        (valid if old else todo).append(item)
    started, initial = time.monotonic(), len(valid)
    finished, failures = initial, []
    atomic_json(root / 'errors.json', failures)
    def progress(current=''):
        elapsed = time.monotonic() - started
        rate = (finished - initial) / elapsed if elapsed else 0
        remaining = len(items) - finished
        state = dict(total=len(items), completed=finished, remaining=remaining,
                     already_completed=initial, workers=args.workers, batch_size=args.batch_size,
                     elapsed_seconds=round(elapsed, 1), eta_seconds=round(remaining / rate) if rate else None,
                     failures=failures, current=current, stopped=stopped(root), pid=os.getpid(),
                     updated_at=datetime.now(timezone.utc).isoformat())
        atomic_json(root / 'progress.json', state)
        print(f'{finished}/{len(items)}; remaining {remaining}; ETA {eta(remaining/rate) if rate else "estimating"}; {current}', flush=True)
    progress('start')
    # Completed outputs survive even if the process died before a batch index commit.
    indexes(root)
    for begin in range(0, len(todo), args.batch_size):
        if stopped(root):
            break
        batch = todo[begin:begin + args.batch_size]
        print(f'Batch {begin//args.batch_size+1}: {len(batch)} items', flush=True)
        successful = []
        # Only workers tasks in flight, so stop never waits for a queued whole batch.
        iterator = iter(batch)
        with futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
            active = {}
            for _ in range(args.workers):
                item = next(iterator, None)
                if item is not None:
                    active[pool.submit(process, item, source, root)] = item
            while active:
                done, _ = futures.wait(active, return_when=futures.FIRST_COMPLETED, timeout=2)
                if not done:
                    continue
                for f in done:
                    item = active.pop(f)
                    try:
                        result = f.result()
                        successful.append(item)
                        finished += 1
                        progress(result['bucket'] + '/' + result['title'])
                    except Exception as exc:
                        error = dict(id=item['id'], error=str(exc))
                        failures.append(error)
                        atomic_json(root / 'errors.json', failures)
                        print('ERROR ' + json.dumps(error), flush=True)
                    if not stopped(root):
                        next_item = next(iterator, None)
                        if next_item is not None:
                            active[pool.submit(process, next_item, source, root)] = next_item
        # Durable manifests and verified search DBs BEFORE any original audio deletion.
        indexes(root)
        # Also clean outputs from an interrupted previous run, without re-downloading.
        for item in items:
            result = complete(root, item['id'])
            if result and result['status'] in ('done', 'excluded'):
                for fmt in item['encodings']:
                    (source / 'audio' / (item['id'] + '.' + fmt)).unlink(missing_ok=True)
        progress('batch committed; processed originals removed')
        if stopped(root):
            break
    progress('stopped' if stopped(root) else ('finished with errors' if failures else 'finished'))
    return 1 if failures else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('command', choices=['run', 'status', 'stop'])
    ap.add_argument('--source', type=Path)
    ap.add_argument('--out', type=Path, required=True)
    ap.add_argument('--workers', type=int, choices=range(1, 9), default=4)
    ap.add_argument('--batch-size', type=int, default=100)
    ap.add_argument('--limit', type=int, default=0)
    args = ap.parse_args()
    if args.command == 'run':
        if not args.source:
            ap.error('--source is required for run')
        if args.batch_size < 1 or args.limit < 0:
            ap.error('Invalid batch size/limit')
        return run(args)
    if args.command == 'stop':
        args.out.mkdir(parents=True, exist_ok=True)
        (args.out / 'STOP').touch()
        print('Stop requested. In-flight files finish, indexes commit, then processing stops.')
        return 0
    p = args.out / 'progress.json'
    if not p.exists():
        print('Not started')
        return 0
    state = json.loads(p.read_text())
    state_lock = (args.out / 'run.lock').open('a+')
    try:
        fcntl.flock(state_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        running = False
    except BlockingIOError:
        running = True
    print(('RUNNING' if running else 'STOPPED / FINISHED') + ': '
          + str(state['completed']) + '/' + str(state['total'])
          + '; remaining ' + str(state['remaining']))
    seconds = state.get('eta_seconds')
    print('ETA: ' + (eta(seconds) if seconds is not None and running else 'not running / estimating'))
    print('Last: ' + state.get('current', '') + '; errors: ' + str(len(state.get('failures', []))))
    print('Updated: ' + state.get('updated_at', ''))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as exc:
        print('ERROR:', exc, file=sys.stderr)
        sys.exit(1)
