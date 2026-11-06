#!/usr/bin/env python3
"""Inventory/download BigSoundBank originals with resumable per-sound metadata.

Examples:
  python3 tools/sound_bank/download_bigsoundbank.py inventory --out /tmp/bsb
  python3 tools/sound_bank/download_bigsoundbank.py download --out /tmp/bsb --workers 4
  python3 tools/sound_bank/download_bigsoundbank.py download --out /tmp/bsb --limit 10

Inventory reads public catalogue/detail pages and estimates bytes without fetching
any audio. Downloads are for evaluation, not automatic clearance for publication.
Only explicitly CC0-labelled pages qualify for audio download. Dependencies: curl.
"""
import argparse
import concurrent.futures
import hashlib
import html
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.parse import urljoin, urlparse, parse_qs

BASE = 'https://bigsoundbank.com'
CC0 = 'https://creativecommons.org/publicdomain/zero/1.0/'


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []

    def handle_starttag(self, tag, attrs):
        if tag == 'a':
            self.links.append(dict(attrs).get('href', ''))


def write_json(path, data):
    temp = path.with_suffix(path.suffix + '.tmp')
    temp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    temp.replace(path)


def fetch(url, path):
    """Curl does TLS verification, bounded retries, timeout and HTTP error checks."""
    parsed = urlparse(url)
    if parsed.scheme != 'https' or parsed.hostname != 'bigsoundbank.com':
        raise ValueError('Unexpected source URL: ' + url)
    tmp = path.with_suffix(path.suffix + '.part')
    try:
        r = subprocess.run([
            'curl', '--fail', '--location', '--silent', '--show-error',
            '--proto', '=https', '--proto-redir', '=https',
            '--connect-timeout', '15', '--max-time', '180',
            '--retry', '2', '--retry-delay', '2', '--retry-max-time', '400',
            '--user-agent', 'MyTTS-SoundBank-Evaluation/1.0',
            '--output', str(tmp), url,
        ], capture_output=True, text=True)
        if r.returncode:
            raise RuntimeError(r.stderr.strip()[-600:])
        if not tmp.stat().st_size:
            raise ValueError('Empty response: ' + url)
        tmp.replace(path)
    finally:
        tmp.unlink(missing_ok=True)


def cached_page(url, path):
    if not path.exists():
        fetch(url, path)
    return path.read_text(errors='replace')


def links(text):
    p = Links()
    p.feed(text)
    return [urljoin(BASE, h) for h in p.links]


def sound_links(text):
    return {u for u in links(text) if urlparse(u).hostname == 'bigsoundbank.com'
            and re.fullmatch(r'/[^/]+-s\d+\.html', urlparse(u).path)}


def page_numbers(text):
    return [int(q['page'][0]) for u in links(text)
            if urlparse(u).path == '/search'
            and (q := parse_qs(urlparse(u).query)) .get('page', [''])[0].isdigit()]


def audio_object(text):
    for source in re.findall(
            r'<script\b[^>]*type=[\"\x27]application/ld\+json[\"\x27][^>]*>(.*?)</script>',
            text, re.S | re.I):
        data = json.loads(source)
        pending = data if isinstance(data, list) else [data]
        while pending:
            x = pending.pop()
            if not isinstance(x, dict):
                continue
            if x.get('@type') == 'AudioObject':
                return x
            pending.extend(x.get('@graph', []))
    raise ValueError('No AudioObject metadata')


def inspect_sound(url, root):
    sid = re.search(r'-s(\d+)\.html$', url).group(1)
    page = root / 'pages' / (sid + '.html')
    text = cached_page(url, page)
    x = audio_object(text)
    encodings = {}
    for e in x.get('encoding', []):
        media = e.get('encodingFormat', '')
        fmt = {'audio/wav': 'wav', 'audio/mpeg': 'mp3', 'audio/mp4': 'm4a',
               'audio/flac': 'flac'}.get(media)
        if fmt:
            encodings[fmt] = {'url': e['contentUrl'], 'bytes': int(e['contentSize'])}
    if not encodings:
        raise ValueError('No supported audio encodings')
    author = x.get('author', {})
    a = text.find('Technical details')
    b = text.find('Other interesting', a)
    details = html.unescape(re.sub('<[^>]*>', ' ', text[a:b if b > a else a + 5000]))
    details = re.sub(r'\s+', ' ', details)
    actual_author = re.search(r'Author\s*:\s*(.*?)\s*Sound number\s*:', details)
    schema_author = author
    if actual_author:
        author = {'@type': 'Person', 'name': actual_author.group(1).strip()}
    return dict(id=sid, title=x.get('name', '').split(' — ')[0],
                description=x.get('description', ''), author=author, schema_author=schema_author,
                source_url=url, source_license_url=x.get('license'),
                license='CC0-1.0' if 'CC0 (public domain)' in text else 'UNVERIFIED',
                license_url=CC0 if 'CC0 (public domain)' in text else None,
                checked_at=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                source_duration=x.get('duration'), encodings=encodings,
                publication_review='pending: provenance and listening', changes=[])


def sha256(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def download_sound(item, root, fmt):
    if item['license'] != 'CC0-1.0':
        raise ValueError('Not explicitly CC0: ' + item['source_url'])
    enc = item['encodings'].get(fmt)
    if not enc:
        raise ValueError('Format unavailable: ' + fmt)
    path = root / 'audio' / (item['id'] + '.' + fmt)
    receipt = root / 'receipts' / (path.name + '.json')
    if path.exists() and receipt.exists():
        old = json.loads(receipt.read_text())
        if old.get('url') == enc['url'] and path.stat().st_size == old.get('bytes'):
            if sha256(path) == old.get('sha256'):
                return 'cached', path.stat().st_size
    fetch(enc['url'], path)
    if path.stat().st_size != enc['bytes']:
        path.unlink()
        raise ValueError('Audio size differs from source metadata: ' + item['id'])
    with path.open('rb') as f:
        header = f.read(12)
    if fmt == 'wav' and not (header[:4] in (b'RIFF', b'RF64') and header[8:12] == b'WAVE'):
        path.unlink()
        raise ValueError('Response is not a WAV file: ' + item['id'])
    write_json(receipt, dict(file=str(path.relative_to(root)), url=enc['url'],
                            bytes=path.stat().st_size, sha256=sha256(path),
                            source_id=item['id'], license=item['license']))
    return 'downloaded', path.stat().st_size


def parallel(jobs, fn, workers, label):
    results, errors = [], []
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(fn, job): job for job in jobs}
        for i, future in enumerate(concurrent.futures.as_completed(futures), 1):
            try:
                results.append(future.result())
            except Exception as exc:
                job = futures[future]
                errors.append({'job': job.get('id') if isinstance(job, dict) else job,
                               'error': str(exc)})
                print('ERROR:', errors[-1], file=sys.stderr, flush=True)
            if i % 25 == 0 or i == len(futures):
                print(f'{label}: {i}/{len(futures)}, errors={len(errors)}', flush=True)
    return results, errors


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('command', choices=['inventory', 'download'])
    ap.add_argument('--out', type=Path, required=True)
    ap.add_argument('--workers', type=int, default=4, choices=range(1, 9))
    ap.add_argument('--format', choices=['wav', 'mp3', 'm4a', 'flac'], default='wav')
    ap.add_argument('--limit', type=int, default=0, help='Inspect/download first N sounds; 0 = all')
    ap.add_argument('--catalog-pages', type=int, default=0, help='Limit catalogue pages; 0 = all')
    ap.add_argument('--download-limit', type=int, default=0, help='Download at most N eligible files; 0 = all')
    ap.add_argument('--url', action='append', help='Specific sound page; repeat for multiple sounds')
    args = ap.parse_args()
    if args.limit < 0 or args.catalog_pages < 0 or args.download_limit < 0:
        ap.error('Limits must be >= 0')
    started = time.monotonic()
    root = args.out.resolve()
    for folder in ['catalog', 'pages', 'metadata', 'audio', 'receipts']:
        (root / folder).mkdir(parents=True, exist_ok=True)
    errors = []
    if args.url:
        urls = sorted(set(args.url))
        total_pages = scanned_pages = 0
    else:
        first = cached_page(BASE + '/search', root / 'catalog' / '1.html')
        total_pages = max(page_numbers(first), default=1)
        scanned_pages = min(total_pages, args.catalog_pages or total_pages)
        def catalog(n):
            text = cached_page(BASE + f'/search?page={n}', root / 'catalog' / f'{n}.html')
            return sound_links(text)
        pages, errors = parallel(list(range(2, scanned_pages + 1)), catalog,
                                 args.workers, 'catalog')
        found = sound_links(first)
        for page in pages:
            found.update(page)
        urls = sorted(found, key=lambda u: int(re.search(r'-s(\d+)\.html$', u).group(1)))
    discovered = len(urls)
    if args.limit:
        urls = urls[:args.limit]
    print(f'Discovered {discovered} sound pages; inspecting {len(urls)}', flush=True)
    def inspect(url):
        # Individual JSON persists immediately; interruption never discards completed work.
        item = inspect_sound(url, root)
        write_json(root / 'metadata' / (item['id'] + '.json'), item)
        return item
    items, detail_errors = parallel(urls, inspect, args.workers, 'metadata')
    errors.extend(detail_errors)
    items.sort(key=lambda x: int(x['id']))
    metadata_seconds = time.monotonic() - started
    summary = dict(metadata_seconds=round(metadata_seconds, 3), catalog_pages_available=total_pages, catalog_pages_scanned=scanned_pages,
                   discovered=discovered, inspected=len(items), errors=errors,
                   scope='explicit URLs' if args.url else ('partial catalogue' if scanned_pages < total_pages or len(urls) < discovered else 'full catalogue'),
                   formats={})
    for fmt in ['wav', 'mp3', 'm4a', 'flac']:
        available = [s['encodings'][fmt]['bytes'] for s in items if fmt in s['encodings']]
        summary['formats'][fmt] = dict(files=len(available), bytes=sum(available), GiB=round(sum(available)/1024**3, 4))
    write_json(root / 'inventory.json', {'summary': summary, 'sounds': items})
    (root / 'CREDITS.md').write_text('# BigSoundBank evaluation downloads\n\n'
        'Source metadata and license labels; publication/listening review pending.\n'
        'Original files, no audio processing.\n\n' + '\n\n'.join(
        f"- {s['id']}: {s['title']}\n  Author: {json.dumps(s['author'], ensure_ascii=False)}\n"
        f"  Source: {s['source_url']}\n  License: {s['license']} {s['license_url'] or ''}\n"
        f"  Source terms: {s['source_license_url']}" for s in items) + '\n')
    cached_page(BASE + '/licenses.html', root / 'source-license.html')
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)
    if args.command == 'download':
        eligible = [s for s in items if s['license'] == 'CC0-1.0' and args.format in s['encodings']]
        skipped = len(items) - len(eligible)
        if args.download_limit:
            eligible = eligible[:args.download_limit]
        audio_started = time.monotonic()
        downloaded, failures = parallel(eligible, lambda s: download_sound(s, root, args.format),
                                        args.workers, 'audio')
        elapsed = time.monotonic() - audio_started
        transferred = sum(n for status, n in downloaded if status == 'downloaded')
        write_json(root / 'download-summary.json', dict(audio_seconds=round(elapsed, 3),
                   total_seconds=round(time.monotonic() - started, 3),
                   transferred_bytes=transferred, MB_per_second=round(transferred / 1e6 / max(elapsed, 0.001), 3),
                   completed=len(downloaded),
                   cached=sum(s == 'cached' for s, _ in downloaded), skipped=skipped,
                   bytes=sum(n for _, n in downloaded), errors=failures))
        errors.extend(failures)
        print(f'Audio: {len(downloaded)} complete, {skipped} skipped; see download-summary.json')
    return 1 if errors else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print('\nStopped. Rerun the same command to resume.', file=sys.stderr)
        sys.exit(130)
