#!/usr/bin/env python3
"""Download the pinned text-only v81 bundle; never keep unverified final files."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import concurrent.futures
import shutil

def download_large(url, partial, size):
    start = partial.stat().st_size if partial.exists() else 0
    ranges = [(s, min(s + 512 * 1024 * 1024, size) - 1)
              for s in range(start, size, 512 * 1024 * 1024)]
    def fetch(bounds):
        begin, end = bounds
        chunk = pathlib.Path(str(partial) + f'.range-{begin}')
        expected = end - begin + 1
        if not chunk.exists() or chunk.stat().st_size != expected:
            subprocess.run(['curl', '-fsSL', '--retry', '3', '--range', f'{begin}-{end}',
                            '--max-filesize', str(expected), url, '-o', str(chunk)], check=True)
        if chunk.stat().st_size != expected:
            raise RuntimeError('Incomplete range: ' + str(begin))
        print('Downloaded range:', begin, end, flush=True)
        return chunk
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        chunks = list(pool.map(fetch, ranges))
    with partial.open('ab') as destination:
        for chunk in chunks:
            with chunk.open('rb') as source:
                shutil.copyfileobj(source, destination, 4 * 1024 * 1024)
    # Leave range files until the combined hash is verified by the caller.
    return chunks

def sha256(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(4 * 1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('destination', type=pathlib.Path)
    args = parser.parse_args()
    pinned = json.loads((pathlib.Path(__file__).parent / 'app/src/main/assets/model-files.json').read_text())
    args.destination.mkdir(parents=True, exist_ok=True)
    for spec in pinned['files']:
        out = args.destination / spec['name']
        if out.is_file() and out.stat().st_size == spec['size'] and sha256(out) == spec['sha256']:
            print('Verified existing:', out.name, flush=True)
            continue
        partial = out.with_suffix(out.suffix + '.partial')
        url = f"https://huggingface.co/{pinned['repo']}/resolve/{pinned['revision']}/v81/{spec['name']}"
        print('Downloading:', out.name, spec['size'], flush=True)
        chunks = []
        if spec['size'] > 2 * 1024**3:
            chunks = download_large(url, partial, spec['size'])
        else:
            subprocess.run(['curl', '-fsSL', '--retry', '3', '-C', '-', url, '-o', str(partial)], check=True)
        if partial.stat().st_size != spec['size'] or sha256(partial) != spec['sha256']:
            raise RuntimeError('Integrity check failed: ' + out.name)
        partial.replace(out)
        for chunk in chunks:
            chunk.unlink()
        print('SHA256 OK:', out.name, flush=True)

if __name__ == '__main__':
    main()
