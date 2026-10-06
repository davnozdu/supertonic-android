#!/usr/bin/env python3
"""Download the pinned text-only v81 bundle; never keep unverified final files."""
import argparse
import hashlib
import json
import pathlib
import subprocess

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
        subprocess.run(['curl', '-fL', '--retry', '3', '-C', '-', url, '-o', str(partial)], check=True)
        if partial.stat().st_size != spec['size'] or sha256(partial) != spec['sha256']:
            raise RuntimeError('Integrity check failed: ' + out.name)
        partial.replace(out)
        print('SHA256 OK:', out.name, flush=True)

if __name__ == '__main__':
    main()
